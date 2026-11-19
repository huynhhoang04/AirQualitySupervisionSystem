"""Preflight for the AI worker against a deployed backend.

Answers one question: "can this laptop serve forecasts for that backend right now?", and prints the
reason when the answer is no. It is read-only by default — the forecast it computes is never
uploaded unless you pass --upload.

Usage:
    PYTHONPATH=. .venv-chronos/bin/python tools/check_worker.py
    PYTHONPATH=. .venv-chronos/bin/python tools/check_worker.py --upload
    PYTHONPATH=. .venv/bin/python tools/check_worker.py --engine sklearn   # no torch needed
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from typing import Any

import httpx


def section(title: str) -> None:
    print(f"\n=== {title} ===")


def check_backend(base: str) -> dict[str, Any]:
    section(f"backend {base}")
    result: dict[str, Any] = {"ok": False}
    try:
        health = httpx.get(f"{base}/actuator/health", timeout=30)
        print(f"health: HTTP {health.status_code} {health.text[:80]}")
    except Exception as error:  # noqa: BLE001
        print(f"health: UNREACHABLE ({error})")
        return result

    devices = httpx.get(f"{base}/api/v1/devices", timeout=30).json()
    ids = [device["device_id"] for device in devices]
    print(f"devices: {ids or 'none'}")
    if not ids:
        print("no paired device — nothing to forecast")
        return result

    status = httpx.get(f"{base}/api/v1/ai/status", params={"device_id": ids[0]}, timeout=30).json()
    print(f"workers: {[(w['id'], w['online']) for w in status['workers']] or 'none'}")
    print(f"latest telemetry: {status.get('latest_telemetry_recorded_at')}")
    print(f"latest forecast:  {status.get('latest_forecast_issued_at')}")

    result.update({"ok": True, "device_id": ids[0], "status": status})
    return result


def check_paths(base: str, device_id: str) -> dict[str, Any]:
    section("V6.1–V6.3 endpoints")
    result: dict[str, Any] = {}
    for label, path in (
        ("snapshot", f"/api/v1/devices/{device_id}/live/snapshot"),
        ("accuracy", f"/api/v1/devices/{device_id}/forecast/accuracy?horizon=5&hours=1"),
        ("path", f"/api/v1/devices/{device_id}/forecast/path"),
        ("samples", f"/internal/v1/ai/devices/{device_id}/samples?after_id=0&limit=2"),
        ("actuator", f"/internal/v1/ai/devices/{device_id}/actuator-reports?after_id=0&limit=2"),
    ):
        response = httpx.get(f"{base}{path}", timeout=60)
        result[label] = response.status_code
        note = ""
        if response.status_code == 200:
            try:
                body = response.json()
                if label == "snapshot":
                    note = f"{len(body.get('samples', []))} samples, cursor {body.get('cursor')}"
                elif label == "accuracy":
                    note = f"matched {body.get('matched')}, mae {body.get('mae_ug_m3')}"
                elif label == "path":
                    note = f"{len(body.get('values', []))} values"
                elif label in ("samples", "actuator"):
                    note = f"{len(body.get('items', []))} items"
            except ValueError:
                note = "non-JSON body"
        elif response.status_code == 404 and label == "path":
            note = "no curve stored yet (expected until a Chronos run is uploaded)"
        print(f"{label:9s} HTTP {response.status_code}  {note}")
    return result


def check_context(base: str, device_id: str, minutes: int) -> dict[str, Any]:
    section("context readiness")
    from app.chronos_model import CONTEXT_STEPS, NotReady, build_context, ensure_ready, longest_gap_steps

    rows = httpx.get(f"{base}/internal/v1/ai/devices/{device_id}/recent",
                     params={"minutes": minutes}, timeout=60).json()
    print(f"rows in last {minutes} min: {len(rows)}")
    if not rows:
        print("no telemetry yet")
        return {"ready": False}

    try:
        context = build_context(rows, now=None, require_fresh=False)
    except NotReady as error:
        print(f"context: not buildable — {error}")
        return {"ready": False}

    span_seconds = 0.0
    if len(rows) > 1:
        from app.chronos_model import _instant  # noqa: PLC2701 — same package, single use

        times = sorted(t for t in (_instant(r.get("recorded_at")) for r in rows) if t is not None)
        span_seconds = (times[-1] - times[0]).total_seconds()

    gap = longest_gap_steps(context.target)
    print(f"coverage:      {context.coverage:.1%} ({context.steps_present}/{CONTEXT_STEPS} steps)")
    print(f"history span:  {span_seconds / 60:.1f} min (a full context needs "
          f"{CONTEXT_STEPS * 5 / 60:.0f} min)")
    print(f"longest hole:  {gap * 5 / 60:.1f} min")
    print(f"anchor:        {context.anchor_at.isoformat()} pm {context.anchor_pm25} "
          f"age {context.anchor_age_seconds:.1f}s")

    try:
        ensure_ready(context)
        print("readiness:     PASS")
        return {"ready": True}
    except NotReady as error:
        print(f"readiness:     NOT YET — {error}")
        # The gate is 80 % coverage, not a full window: 576 of 720 steps is 48 minutes of data, not
        # 60. Reporting "60 − span" overstated the wait by 12 minutes.
        from app.chronos_model import MIN_CONTEXT_COVERAGE

        needed_steps = int(MIN_CONTEXT_COVERAGE * CONTEXT_STEPS) + 1
        missing = max(0, needed_steps - context.steps_present)
        if missing > 0:
            ready = context.anchor_at.timestamp() + missing * 5
            from datetime import datetime, timezone

            eta = datetime.fromtimestamp(ready, tz=timezone.utc)
            print(f"               {context.steps_present}/{needed_steps} steps; about "
                  f"{missing * 5 / 60:.0f} more minutes → ready near {eta.strftime('%H:%M:%S')} UTC")
        return {"ready": False}


def run_model(engine: str, device_id: str, upload: bool, base: str) -> dict[str, Any]:
    section(f"inference ({engine})")
    from datetime import timedelta

    if engine == "sklearn":
        from app.backend import BackendClient
        from app.config import Settings
        from app.inference import NotReady as SkNotReady, predict_run
        from app.models import PersistenceBundle

        settings = Settings.from_env()
        client = BackendClient(settings)
        rows = client.recent(device_id, 90)
        actuator = client.recent_actuator(device_id, 90)
        context = client.context(device_id)
        space = context.get("space") or {}
        interval = (context.get("device") or {}).get("telemetry_interval_ms") or 5000
        try:
            payload = predict_run(
                bundle=PersistenceBundle(), rows=rows, actuator_rows=actuator,
                interval_seconds=max(1.0, interval / 1000.0), device_id=device_id,
                model_version="persistence-v2", threshold_pm25=space.get("threshold_pm25"),
                profile_version=space.get("profile_version"), worker_id=settings.worker_id,
            )
        except SkNotReady as error:
            print(f"sklearn engine not ready: {error}")
            return {"ok": False}
        print(f"points: {[(p['horizon_minutes'], p['pm25']) for p in payload['points']]}")
        if upload:
            print("uploading ...")
            print(json.dumps(client.upload_forecast(payload), indent=2)[:400])
        return {"ok": True, "payload": payload}

    from app.chronos_model import ChronosForecaster, NotReady, build_context, ensure_ready

    rows = httpx.get(f"{base}/internal/v1/ai/devices/{device_id}/recent",
                     params={"minutes": 90}, timeout=60).json()
    context = build_context(rows, now=None, require_fresh=False)
    ensure_ready(context)   # the same gate the worker uses

    forecaster = ChronosForecaster.load(device=_device(), model_version="chronos-2-preflight")
    started = datetime.now(timezone.utc)
    forecast = forecaster.forecast_context(context)
    elapsed = (datetime.now(timezone.utc) - started).total_seconds() * 1000
    print(f"inference: {elapsed:.0f} ms on {_device()}")
    print(f"points: {[(p['horizon_minutes'], p['pm25']) for p in forecast['points']]}")
    print(f"curve: {len(forecast['path']['values'])} values, "
          f"anchor {forecast['path']['anchor_pm25']}")
    if not upload:
        print("(dry run — pass --upload to store this run in the backend)")
        return {"ok": True, "forecast": forecast}

    payload = {
        "device_id": device_id,
        "model_version": forecast["data_quality"]["model_version"],
        "input_cutoff": forecast["path"]["anchor_at"],
        "issued_at": datetime.now(timezone.utc).isoformat(),
        "threshold_pm25": None,
        "data_quality": forecast["data_quality"],
        "worker_id": "preflight",
        "points": forecast["points"],
        "path": forecast["path"],
    }
    response = httpx.post(f"{base}/internal/v1/ai/forecast-runs", json=payload, timeout=60)
    print(f"upload: HTTP {response.status_code}")
    if response.status_code >= 400:
        print(response.text[:400])
        return {"ok": False}
    body = response.json()
    print(f"run {body['run']['id']} stored with "
          f"{len(body['path']['values']) if body.get('path') else 0} curve values")
    return {"ok": True}


def _device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:  # noqa: BLE001
        return "cpu"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default="https://aqss-backend.onrender.com")
    parser.add_argument("--device-id", default=None)
    parser.add_argument("--engine", default="chronos", choices=["chronos", "sklearn"])
    parser.add_argument("--minutes", type=int, default=90)
    parser.add_argument("--upload", action="store_true",
                        help="Actually store the forecast (default is a dry run).")
    args = parser.parse_args()

    backend = check_backend(args.backend)
    if not backend["ok"]:
        print("\nBackend is not reachable; nothing else to check.")
        return 1

    device_id = args.device_id or backend["device_id"]
    check_paths(args.backend, device_id)
    readiness = check_context(args.backend, device_id, args.minutes)
    if not readiness.get("ready"):
        print("\nThe model cannot issue a forecast for this device yet — see the numbers above.")
        return 2

    result = run_model(args.engine, device_id, args.upload, args.backend)
    return 0 if result.get("ok") else 3


if __name__ == "__main__":
    sys.exit(main())
