"""Chronos inference loop for the worker: one task per device, newest request wins.

This module is the bridge between `app/chronos_model.py` and the backend. It deliberately does not
import torch at module import time: the sklearn worker must keep running on a machine without the
Chronos environment.

Cadence rules (docs/upgrade-plan/07 §3):

* one inference task per device at a time;
* while it runs, keep only the newest pending request — do not queue every packet;
* the number of coalesced requests is reported, so "we keep up with 5 s" is measured, not assumed.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .backend import BackendClient, BackendError
from .chronos_model import NotReady, ChronosForecaster, build_context
from .config import Settings
from .context_cache import ContextCache
from .inference import recommendations

log = logging.getLogger("aqss.ai.chronos")


@dataclass
class DeviceState:
    device_id: str
    busy: bool = False
    pending: bool = False
    dropped: int = 0
    issued: int = 0
    failed: int = 0
    last_run_at: datetime | None = None
    last_error: str | None = None
    last_latency_ms: float | None = None
    latencies_ms: list[float] = field(default_factory=list)

    def summary(self) -> dict[str, Any]:
        recent = self.latencies_ms[-20:]
        return {
            "dropped": self.dropped,
            "issued": self.issued,
            "failed": self.failed,
            "last_error": self.last_error,
            "last_run_at": self.last_run_at.isoformat() if self.last_run_at else None,
            "p95_ms": round(sorted(recent)[int(0.95 * (len(recent) - 1))], 1) if recent else None,
        }


class ChronosRunner:
    """Owns the loaded pipeline and the per-device inference state."""

    def __init__(self, settings: Settings, client: BackendClient):
        self.settings = settings
        self.client = client
        self.cache = ContextCache(client)
        self.states: dict[str, DeviceState] = {}
        self._lock = threading.Lock()
        self._forecaster: ChronosForecaster | None = None
        self._forecaster_error: str | None = None
        self._next_tick = 0.0

    # ── model lifecycle ─────────────────────────────────────────────────────

    def ensure_forecaster(self) -> ChronosForecaster | None:
        """
        Load once, lazily. A missing CUDA/Chronos install must degrade to "not available" rather
        than take the worker down: measurement and control never depend on this process.
        """
        if self._forecaster is not None:
            return self._forecaster
        if self._forecaster_error is not None:
            return None
        try:
            path = str(self.settings.chronos_adapter) if self.settings.chronos_adapter else self.settings.chronos_model
            device = "cuda" if _cuda_available() else "cpu"
            self._forecaster = ChronosForecaster.load(path, device=device,
                                                      model_version=_version(path))
            log.info("Chronos loaded from %s on %s", path, device)
            return self._forecaster
        except Exception as error:  # noqa: BLE001 — optional dependency
            self._forecaster_error = str(error)[:300]
            log.warning("Chronos unavailable, staying on the sklearn/persistence path: %s", error)
            return None

    @property
    def available(self) -> bool:
        return self._forecaster is not None or self._forecaster_error is None

    # ── loop ────────────────────────────────────────────────────────────────

    def tick(self, devices: list[dict[str, Any]]) -> None:
        """Called from the worker loop; returns immediately, work happens on the device thread."""
        now = time.monotonic()
        if now < self._next_tick:
            return
        self._next_tick = now + max(1, self.settings.inference_interval_seconds)
        for device in devices:
            self.request(device)

    def request(self, device: dict[str, Any]) -> None:
        device_id = device.get("device_id")
        if not device_id:
            return
        with self._lock:
            state = self.states.setdefault(device_id, DeviceState(device_id))
            if state.busy:
                # One task per device: remember that something newer arrived, do not queue it.
                state.pending = True
                state.dropped += 1
                return
            state.busy = True
        threading.Thread(target=self._run_device, args=(device, state), daemon=True,
                         name=f"chronos-{device_id}").start()

    # ── one device ──────────────────────────────────────────────────────────

    def _run_device(self, device: dict[str, Any], state: DeviceState) -> None:
        device_id = device["device_id"]
        started = time.perf_counter()
        try:
            forecaster = self.ensure_forecaster()
            if forecaster is None:
                state.last_error = self._forecaster_error or "Chronos not loaded"
                return
            context = self._build(device_id)
            forecast = forecaster.forecast_context(context)
            service = self._service_context(device_id)
            payload = self._payload(device_id, device, forecast, service)
            self.client.upload_forecast(payload)
            state.issued += 1
            state.last_error = None
        except NotReady as error:
            state.last_error = str(error)[:200]
            log.info("Chronos not ready for %s: %s", device_id, error)
        except BackendError as error:
            state.failed += 1
            state.last_error = str(error)[:200]
            log.warning("forecast upload failed for %s: %s", device_id, error)
        except Exception as error:  # noqa: BLE001 — the loop must survive
            state.failed += 1
            state.last_error = str(error)[:200]
            log.exception("Chronos inference failed for %s", device_id)
        finally:
            latency = (time.perf_counter() - started) * 1000
            state.last_latency_ms = round(latency, 1)
            state.latencies_ms.append(latency)
            state.latencies_ms = state.latencies_ms[-200:]
            state.last_run_at = datetime.now(timezone.utc)
            with self._lock:
                state.busy = False
                rerun = state.pending
                state.pending = False
            if rerun:
                # A newer packet arrived while this run was in flight: run once, not once per packet.
                self.request(device)

    def _build(self, device_id: str) -> Any:
        # `rows_with_fan` attaches the reported fan level to each sample. Plain `rows` left the
        # fan covariate entirely NaN, so the model never saw the strongest signal in the data.
        samples, _ = self.cache.rows_with_fan(device_id)
        return build_context(samples, now=None, require_fresh=True)

    def _service_context(self, device_id: str) -> dict[str, Any]:
        try:
            return self.client.context(device_id)
        except BackendError:
            return {}

    def _payload(self, device_id: str, device: dict[str, Any], forecast: dict[str, Any],
                 service: dict[str, Any]) -> dict[str, Any]:
        space = service.get("space") or {}
        anchor = forecast["path"]["anchor_at"]
        threshold = space.get("threshold_pm25")
        quality = dict(forecast["data_quality"])
        # The one-line advisory the room screen shows. Same rule set the sklearn engine uses, so a
        # switch of engine does not silently remove the warning from the UI.
        quality["recommendations"] = self._recommendations(forecast, threshold, quality)
        return {
            "device_id": device_id,
            "model_version": quality["model_version"],
            "input_cutoff": anchor,
            "issued_at": datetime.now(timezone.utc).isoformat(),
            "profile_version": space.get("profile_version"),
            "threshold_pm25": threshold,
            "data_quality": quality,
            "worker_id": self.settings.worker_id,
            "points": forecast["points"],
            "path": forecast["path"],
        }

    def _recommendations(self, forecast: dict[str, Any], threshold: float | None,
                         quality: dict[str, Any]) -> list[dict[str, str]]:
        if threshold is None:
            return []
        pm = quality.get("pm_at_origin")
        try:
            current = float(pm) if pm is not None else None
        except (TypeError, ValueError):
            current = None
        try:
            co2 = float(quality["co2_at_origin"]) if quality.get("co2_at_origin") is not None else None
        except (TypeError, ValueError):
            co2 = None
        return recommendations(
            pm=current if current is not None else float("nan"),
            points=forecast["points"],
            threshold=float(threshold),
            co2=co2,
            learned=True,
        )

    def status(self) -> dict[str, Any]:
        return {
            "engine": "chronos",
            "available": self.available,
            "error": self._forecaster_error,
            "devices": {device_id: state.summary() for device_id, state in self.states.items()},
        }


def _cuda_available() -> bool:
    try:
        import torch

        return bool(torch.cuda.is_available())
    except Exception:  # noqa: BLE001 — torch is optional
        return False


def _version(path: str) -> str:
    name = path.rstrip("/").split("/")[-1]
    return f"chronos-2-{name}" if name and name != "chronos-2" else path
