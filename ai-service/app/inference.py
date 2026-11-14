"""Turn recent rows into one forecast run."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pandas as pd

from .data import build_dataset
from .features import FEATURE_VERSION, REQUIRED_AT_ORIGIN, build_features, latest_origin
from .models import HORIZONS


class NotReady(Exception):
    """There is not enough valid, fresh data to issue a learned forecast."""


def build_context(rows: list[dict[str, Any]], actuator_rows: list[dict[str, Any]] | None = None,
                  interval_seconds: float = 5.0,
                  now: datetime | None = None) -> tuple[pd.DataFrame, pd.DataFrame, pd.Timestamp]:
    frame = build_dataset(rows, actuator_rows or [], interval_seconds=interval_seconds)
    if frame.empty:
        raise NotReady("no minute buckets in the window")
    features = build_features(frame)
    now_ts = pd.Timestamp(now or datetime.now(timezone.utc))
    origin = latest_origin(features, now_ts)
    if origin is None:
        raise NotReady("no complete, fresh origin")
    row = features.loc[[origin]]
    if bool(row[REQUIRED_AT_ORIGIN].isna().any(axis=None)):
        raise NotReady("a mandatory PM lag is still missing")
    return features, frame, origin


def predict_run(bundle: Any, rows: list[dict[str, Any]], actuator_rows: list[dict[str, Any]] | None,
                interval_seconds: float, device_id: str, model_version: str,
                threshold_pm25: float | None, profile_version: int | None,
                worker_id: str, now: datetime | None = None) -> dict[str, Any]:
    features, frame, origin = build_context(rows, actuator_rows, interval_seconds, now)
    row = features.loc[[origin]]

    points = []
    for horizon in HORIZONS:
        value = float(bundle.predict(row, horizon)[0])
        target = origin + pd.Timedelta(minutes=horizon)
        points.append(
            {
                "horizon_minutes": horizon,
                "target_at": target.to_pydatetime().astimezone(timezone.utc).isoformat(),
                "pm25": round(float(min(500.0, max(0.0, value))), 2),
            }
        )

    recent = frame.loc[frame.index <= origin].tail(60)
    quality = {
        "feature_version": FEATURE_VERSION,
        "origin": origin.to_pydatetime().astimezone(timezone.utc).isoformat(),
        "input_buckets": int(len(recent)),
        "coverage_60m": None if recent.empty else round(float(recent["coverage"].mean()), 3),
        "pm_valid_buckets_60m": int(recent["pm_valid"].sum()),
        "stale_minutes": round((pd.Timestamp(now or datetime.now(timezone.utc)) - origin)
                               .total_seconds() / 60.0, 2),
    }

    return {
        "device_id": device_id,
        "model_version": model_version,
        "input_cutoff": origin.to_pydatetime().astimezone(timezone.utc).isoformat(),
        "issued_at": (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat(),
        "profile_version": profile_version,
        "threshold_pm25": threshold_pm25,
        "data_quality": quality,
        "worker_id": worker_id,
        "points": points,
    }
