"""Minute buckets, labels and actuator alignment.

The rules here are the ones the evaluation document commits to: one-minute buckets ending at t,
median as the representative value, a bucket valid for PM only when it has at least half the
expected samples, and labels taken only from a real sample in the target bucket.
"""

from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any, Iterable

import numpy as np
import pandas as pd

VALID_PM_MIN = 0.0
VALID_PM_MAX = 500.0
DEFAULT_INTERVAL_SECONDS = 5.0


def _number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def parse_instant(value: Any, default: datetime | None = None) -> datetime | None:
    if value is None:
        return default
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value), tz=timezone.utc)
    text = str(value).strip()
    if not text:
        return default
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return default
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def raw_frame(rows: Iterable[dict[str, Any]]) -> pd.DataFrame:
    records = []
    for row in rows:
        recorded = parse_instant(row.get("recorded_at"))
        if recorded is None:
            continue
        records.append(
            {
                "recorded_at": pd.Timestamp(recorded),
                "received_at": pd.Timestamp(parse_instant(row.get("received_at"), recorded)),
                "pm25": _number(row.get("pm25")),
                "co2": _number(row.get("co2")),
                "temperature": _number(row.get("temperature")),
                "humidity": _number(row.get("humidity")),
                "saturated": bool(row.get("pm25_saturated") or False),
                "dust_fault": bool(row.get("dust_fault") or False),
            }
        )
    if not records:
        return pd.DataFrame(
            columns=["recorded_at", "received_at", "pm25", "co2", "temperature", "humidity",
                     "saturated", "dust_fault"]
        )
    return pd.DataFrame(records)


def pm_usable(frame: pd.DataFrame) -> pd.Series:
    pm = pd.to_numeric(frame["pm25"], errors="coerce")
    return pm.between(VALID_PM_MIN, VALID_PM_MAX) & ~frame["saturated"] & ~frame["dust_fault"]


def minute_buckets(rows: Iterable[dict[str, Any]], bucket_seconds: int = 60,
                   interval_seconds: float = DEFAULT_INTERVAL_SECONDS) -> pd.DataFrame:
    frame = raw_frame(rows)
    expected = max(1, int(round(bucket_seconds / max(interval_seconds, 1.0))))
    columns = ["pm25_median", "pm25_min", "pm25_max", "pm25_count", "co2", "temperature",
               "humidity", "sample_count", "expected_count", "coverage", "pm_valid"]
    if frame.empty:
        empty = pd.DataFrame(index=pd.DatetimeIndex([], tz="UTC"), columns=columns)
        return empty

    frame = frame.set_index("recorded_at").sort_index()
    usable = pm_usable(frame.reset_index().set_index("recorded_at"))
    frame = frame.assign(pm_usable=usable, pm_value=frame["pm25"].where(usable))

    bucket = f"{bucket_seconds}s"
    grouped = frame.groupby(pd.Grouper(freq=bucket, label="right", closed="left"))
    result = pd.DataFrame(
        {
            "pm25_median": grouped["pm_value"].median(),
            "pm25_min": grouped["pm_value"].min(),
            "pm25_max": grouped["pm_value"].max(),
            "pm25_count": grouped["pm_value"].count(),
            "co2": grouped["co2"].median(),
            "temperature": grouped["temperature"].median(),
            "humidity": grouped["humidity"].median(),
            "sample_count": grouped["pm25"].count(),
        }
    ).sort_index()
    result.index = result.index.tz_localize("UTC") if result.index.tz is None else result.index.tz_convert("UTC")
    result["expected_count"] = expected
    result["coverage"] = (result["pm25_count"] / expected).clip(upper=1.0)
    result["pm_valid"] = (result["pm25_count"] >= 0.5 * expected) & result["pm25_median"].notna()
    return result


def with_labels(buckets: pd.DataFrame, horizons: tuple[int, ...] = (5, 15, 30)) -> pd.DataFrame:
    if buckets.empty:
        return buckets
    grid = pd.date_range(buckets.index.min(), buckets.index.max(), freq="1min", tz="UTC")
    frame = buckets.reindex(grid)
    frame["pm_valid"] = frame["pm_valid"].fillna(False).astype(bool)
    for horizon in horizons:
        target = frame["pm25_median"].shift(-horizon)
        target_valid = frame["pm_valid"].shift(-horizon).fillna(False).astype(bool)
        frame[f"y{horizon}"] = target.where(target_valid)
    return frame


def actuator_minutes(rows: Iterable[dict[str, Any]], grid: pd.DatetimeIndex) -> pd.DataFrame:
    """Align actuator reports to a minute grid using receive time, not device time."""
    records = []
    for row in rows:
        received = parse_instant(row.get("received_at") or row.get("recorded_at"))
        if received is None:
            continue
        state = str(row.get("state") or "OFF").upper()
        records.append(
            {
                "received_at": pd.Timestamp(received),
                "fan_on": 1.0 if state == "ON" else 0.0,
                "fan_level": float(_number(row.get("level")) or 0.0),
                "fan_mode": 1.0 if str(row.get("mode") or "").upper() == "MANUAL" else 0.0,
            }
        )
    if not records or len(grid) == 0:
        return pd.DataFrame(index=grid, columns=["fan_on", "fan_level", "fan_mode"])

    reports = pd.DataFrame(records).sort_values("received_at")
    left = pd.DataFrame({"minute": grid})
    aligned = pd.merge_asof(
        left,
        reports,
        left_on="minute",
        right_on="received_at",
        direction="backward",
        tolerance=pd.Timedelta(minutes=2),
    ).set_index("minute")
    return aligned[["fan_on", "fan_level", "fan_mode"]]


def build_dataset(rows: Iterable[dict[str, Any]], actuator_rows: Iterable[dict[str, Any]] | None = None,
                  interval_seconds: float = DEFAULT_INTERVAL_SECONDS,
                  horizons: tuple[int, ...] = (5, 15, 30)) -> pd.DataFrame:
    rows = list(rows)
    buckets = minute_buckets(rows, 60, interval_seconds)
    frame = with_labels(buckets, horizons)
    if frame.empty:
        return frame
    # Targets use real measurements, but features may only use packets available at bucket end.
    # Keep those two views separate so a late replay cannot improve a historical forecast.
    causal_rows = []
    for row in rows:
        recorded = parse_instant(row.get("recorded_at"))
        if recorded is None:
            continue
        end = pd.Timestamp(recorded).floor("min") + pd.Timedelta(minutes=1)
        received = parse_instant(row.get("received_at"), recorded)
        if received is not None and pd.Timestamp(received) <= end:
            causal_rows.append(row)
    causal = minute_buckets(causal_rows, 60, interval_seconds).reindex(frame.index)
    for column in buckets.columns:
        frame[column] = causal[column]
    frame["pm_valid"] = frame["pm_valid"].fillna(False).astype(bool)
    frame["coverage"] = frame["coverage"].fillna(0.0)
    fan = actuator_minutes(actuator_rows or [], frame.index)
    for column in ("fan_on", "fan_level", "fan_mode"):
        frame[column] = fan[column]
    return frame
