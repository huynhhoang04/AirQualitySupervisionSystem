"""Local AI worker: claim jobs, train, then issue one forecast per minute.

One process, one training job at a time. It talks to the cloud backend outbound; nothing here opens
an inbound port except the optional /health diagnostics surface in main.py.
"""

from __future__ import annotations

import hashlib
import json
import logging
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .backend import BackendClient
from .config import Settings
from .inference import NotReady, predict_run
from .models import ModelBundle, PersistenceBundle
from .training import NotEnoughData, train_and_evaluate

log = logging.getLogger("aqss.ai.worker")


class Worker:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.client = BackendClient(settings)
        self.settings.artifact_dir.mkdir(parents=True, exist_ok=True)
        self._next_heartbeat = 0.0
        self._next_inference = 0.0
        # Do not queue a retrain at process start; wait one full interval first.
        self._next_train_check = time.monotonic() + max(1, settings.train_interval_hours) * 3600
        # Consecutive-run state for the two-run breach rule. In-memory on purpose: the backend
        # deduplicates an active alert, so a restart cannot create a second one.
        self._alert_state: dict[str, dict[str, Any]] = {}

    # ── loop ────────────────────────────────────────────────────────────────

    def run_forever(self) -> None:
        log.info("worker %s starting, backend=%s", self.settings.worker_id, self.settings.backend_url)
        while True:
            now = time.monotonic()
            try:
                if now >= self._next_heartbeat:
                    self.heartbeat()
                    self._next_heartbeat = now + self.settings.heartbeat_seconds

                job = self.client.claim(self.settings.worker_id)
                if job:
                    self.handle_job(job)
                elif now >= self._next_inference:
                    self.infer_all()
                    self._next_inference = now + self.settings.inference_seconds

                if now >= self._next_train_check:
                    self.schedule_training()
                    self._next_train_check = now + max(1, self.settings.train_interval_hours) * 3600
            except Exception as error:  # noqa: BLE001 — the worker must survive a bad cycle
                log.warning("cycle failed: %s", error)
            time.sleep(1)

    def heartbeat(self) -> None:
        pointer = self._any_pointer_path()
        self.client.heartbeat(self.settings.worker_id, str(pointer) if pointer else None)

    def schedule_training(self) -> None:
        """Queue one retrain per device at the configured interval; the backend rejects duplicates."""
        for device in self.client.devices():
            device_id = device.get("device_id")
            if not device_id:
                continue
            try:
                job = self.client.create_job(device_id, "train")
                log.info("scheduled retrain %s for %s", job.get("id"), device_id)
            except Exception as error:  # noqa: BLE001 — a conflict just means a job already exists
                log.debug("retrain not queued for %s: %s", device_id, error)

    # ── jobs ────────────────────────────────────────────────────────────────

    def handle_job(self, job: dict[str, Any]) -> None:
        job_id = job["id"]
        lease = job["lease_token"]
        kind = job.get("type")
        if kind != "train":
            self.client.complete(job_id, lease, {"skipped": True}, f"unsupported job type: {kind}")
            return
        try:
            self.train(job)
        except NotEnoughData as error:
            self.client.complete(job_id, lease, {"status": "insufficient_data"}, str(error))
        except Exception as error:  # noqa: BLE001
            log.exception("train job %s failed", job_id)
            self.client.complete(job_id, lease, None, str(error))

    def train(self, job: dict[str, Any]) -> None:
        job_id = job["id"]
        lease = job["lease_token"]
        device_id = job.get("device_id")
        if not device_id:
            raise ValueError("train job has no device_id")

        self.client.progress(job_id, lease, 5, "snapshot")
        now = datetime.now(timezone.utc)
        start = now - timedelta(days=self.settings.max_history_days)
        snapshot = self.client.create_snapshot(device_id, start.isoformat(), now.isoformat())

        self.client.progress(job_id, lease, 15, "download")
        rows = self.client.fetch_telemetry(device_id, snapshot["id"])
        actuator = self.client.fetch_actuator(device_id, snapshot["id"])
        context = self.client.context(device_id)
        interval_ms = (context.get("device") or {}).get("telemetry_interval_ms") or 5000
        interval_seconds = max(1.0, float(interval_ms) / 1000.0)

        self.client.progress(job_id, lease, 30, "features")
        outcome = train_and_evaluate(rows, actuator, interval_seconds=interval_seconds)

        self.client.progress(job_id, lease, 80, "artifact")
        algorithm = outcome["metrics"]["algorithm"]
        version = (f"pm25-{algorithm}-{outcome['metrics']['feature_version']}-"
                   f"{now.strftime('%Y%m%dT%H%M%S')}")
        artifact = self._artifact_path(device_id, version)
        outcome["bundle"].save(artifact)
        artifact_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()

        self.client.progress(job_id, lease, 90, "register")
        model = self.client.register_model({
            "device_id": device_id,
            "version": version,
            "algorithm": algorithm,
            "feature_version": outcome["metrics"]["feature_version"],
            "dataset_snapshot_id": snapshot["id"],
            "dataset_hash": snapshot.get("snapshot_hash"),
            "params": outcome["metrics"].get("params"),
            "train_cutoff": outcome.get("train_cutoff"),
            "metrics": outcome["metrics"],
            "artifact_hash": artifact_hash,
            "artifact_path": str(artifact),
            "status": outcome["status"],
            "promotion_reason": outcome["promotion_reason"],
        })

        if outcome["status"] == "PRODUCTION":
            self._write_pointer(device_id, version, artifact, model.get("id"))

        summary = {
            "model_version": version,
            "model_id": model.get("id"),
            "status": outcome["status"],
            "promotion_reason": outcome["promotion_reason"],
            "metrics": outcome["metrics"],
            "artifact": str(artifact),
            "artifact_hash": artifact_hash,
            "snapshot_id": snapshot["id"],
        }
        self.client.complete(job_id, lease, summary, None)
        log.info("trained %s for %s: %s", version, device_id, outcome["promotion_reason"])

    # ── inference ───────────────────────────────────────────────────────────

    def infer_all(self) -> None:
        for device in self.client.devices():
            try:
                self.infer_device(device)
            except NotReady as error:
                log.debug("not ready for %s: %s", device.get("device_id"), error)
            except Exception as error:  # noqa: BLE001
                log.warning("inference failed for %s: %s", device.get("device_id"), error)

    def infer_device(self, device: dict[str, Any]) -> None:
        device_id = device["device_id"]
        context = self.client.context(device_id)
        space = context.get("space") or {}
        interval_ms = (context.get("device") or device).get("telemetry_interval_ms") or 5000
        interval_seconds = max(1.0, float(interval_ms) / 1000.0)

        rows = self.client.recent(device_id, 90)
        actuator = self.client.recent_actuator(device_id, 90)
        bundle, model_version = self.load_bundle(device_id)

        payload = predict_run(
            bundle,
            rows,
            actuator,
            interval_seconds,
            device_id=device_id,
            model_version=model_version,
            threshold_pm25=space.get("threshold_pm25"),
            profile_version=space.get("profile_version"),
            worker_id=self.settings.worker_id,
        )
        view = self.client.upload_forecast(payload)
        self._update_alert(device_id, view, space.get("threshold_pm25"), payload["points"])

    def _update_alert(self, device_id: str, view: dict[str, Any], threshold: float | None,
                      points: list[dict[str, Any]]) -> None:
        """Two consecutive breach forecasts create an alert; three clear runs resolve it."""
        if threshold is None:
            return
        breaching = [point for point in points if point["pm25"] >= threshold]
        state = self._alert_state.setdefault(device_id, {"breach": 0, "clear": 0, "active_id": None})

        if breaching:
            state["breach"] += 1
            state["clear"] = 0
            if state["breach"] >= 2 and not state["active_id"]:
                earliest = min(breaching, key=lambda point: point["horizon_minutes"])
                alert = self.client.create_alert({
                    "device_id": device_id,
                    "forecast_run_id": (view.get("run") or {}).get("id"),
                    "type": "forecast_breach",
                    "threshold_snapshot": threshold,
                    "horizon_minutes": earliest["horizon_minutes"],
                    "reason": (
                        f"dự báo vượt ngưỡng {threshold:.0f} µg/m³ tại +"
                        f"{earliest['horizon_minutes']} phút trong hai run liên tiếp"
                    ),
                    "lifecycle": "ACTIVE",
                })
                state["active_id"] = alert.get("id")
                log.info("alert %s active for %s", state["active_id"], device_id)
        else:
            state["breach"] = 0
            state["clear"] += 1
            if state["clear"] >= 3 and state["active_id"]:
                self.client.resolve_alerts(device_id, "RESOLVED")
                log.info("alert resolved for %s after three clear runs", device_id)
                state["active_id"] = None

    # ── local artifacts ─────────────────────────────────────────────────────

    def _device_dir(self, device_id: str) -> Path:
        safe = "".join(char if char.isalnum() or char in "-_." else "_" for char in device_id)
        path = self.settings.artifact_dir / safe
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _artifact_path(self, device_id: str, version: str) -> Path:
        return self._device_dir(device_id) / f"{version}.joblib"

    def _pointer_path(self, device_id: str) -> Path:
        return self._device_dir(device_id) / "current.json"

    def _write_pointer(self, device_id: str, version: str, artifact: Path, model_id: str | None) -> None:
        pointer = {
            "version": version,
            "artifact": str(artifact),
            "model_id": model_id,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        self._pointer_path(device_id).write_text(json.dumps(pointer), encoding="utf-8")

    def _any_pointer_path(self) -> Path | None:
        return next(self.settings.artifact_dir.glob("*/current.json"), None)

    def load_bundle(self, device_id: str) -> tuple[Any, str]:
        pointer_path = self._pointer_path(device_id)
        if pointer_path.exists():
            try:
                pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
                artifact = Path(pointer["artifact"])
                if artifact.exists():
                    return ModelBundle.load(artifact), pointer["version"]
                log.warning("pointer exists but artifact is missing for %s", device_id)
            except Exception as error:  # noqa: BLE001
                log.warning("could not load pointer for %s: %s", device_id, error)
        return PersistenceBundle(), "persistence-v1"
