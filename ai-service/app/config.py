"""Configuration for the local AI worker.

Everything comes from the environment. The worker never holds a database credential: it talks to
the backend's internal API with a bearer token.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    backend_url: str
    worker_token: str
    worker_id: str
    artifact_dir: Path
    snapshot_dir: Path
    poll_seconds: int
    inference_seconds: int
    heartbeat_seconds: int
    train_interval_hours: int
    max_history_days: int
    run_worker: bool
    request_timeout: float
    forecast_engine: str
    chronos_model: str
    chronos_adapter: Path | None
    inference_interval_seconds: int

    @classmethod
    def from_env(cls) -> "Settings":
        adapter = os.getenv("CHRONOS_ADAPTER", "").strip()
        return cls(
            backend_url=os.getenv("BACKEND_URL", "http://localhost:8080").rstrip("/"),
            worker_token=os.getenv("AI_WORKER_TOKEN", ""),
            worker_id=os.getenv("WORKER_ID", "local-worker"),
            artifact_dir=Path(os.getenv("ARTIFACT_DIR", "artifacts")),
            snapshot_dir=Path(os.getenv("SNAPSHOT_DIR", "snapshots")),
            poll_seconds=_int("POLL_SECONDS", 10),
            inference_seconds=_int("INFERENCE_SECONDS", 60),
            heartbeat_seconds=_int("HEARTBEAT_SECONDS", 15),
            train_interval_hours=_int("TRAIN_INTERVAL_HOURS", 6),
            max_history_days=_int("MAX_HISTORY_DAYS", 14),
            run_worker=os.getenv("RUN_WORKER", "0") == "1",
            request_timeout=_float("REQUEST_TIMEOUT_SECONDS", 30.0),
            # "sklearn" keeps the feature-v2 worker; "chronos" issues a 360-step curve.
            forecast_engine=os.getenv("FORECAST_ENGINE", "sklearn").strip().lower(),
            chronos_model=os.getenv("CHRONOS_MODEL", "amazon/chronos-2"),
            chronos_adapter=Path(adapter) if adapter else None,
            # Chronos runs on every packet; the 60 s default belongs to the minute-bucket engine.
            inference_interval_seconds=_int("CHRONOS_INFERENCE_SECONDS", 5),
        )
