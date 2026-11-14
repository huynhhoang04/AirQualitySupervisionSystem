"""feature-v1 — the same function for training and inference.

Causality rules: every column at origin t is computed from buckets that end at or before t.
No centred windows, no backward fill, no future join.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

FEATURE_VERSION = "feature-v2"

PM_LAGS = (1, 2, 3, 5, 10, 15, 30, 60)
ROLL_WINDOWS = (5, 15, 30, 60)
FAN_WINDOWS = (5, 15, 30)

REQUIRED_AT_ORIGIN = ["pm_now"] + [f"pm_lag_{lag}" for lag in PM_LAGS]


class InsufficientData(Exception):
    """Not enough history or a mandatory PM feature is missing."""


def build_features(frame: pd.DataFrame) -> pd.DataFrame:
    if frame.empty or "pm25_median" not in frame.columns:
        raise InsufficientData("no minute buckets")
    if len(frame) < max(PM_LAGS) + 1:
        raise InsufficientData(f"need at least {max(PM_LAGS) + 1} minutes, have {len(frame)}")

    data = frame.sort_index().copy()
    pm = pd.to_numeric(data["pm25_median"], errors="coerce").astype(float)
    pm = pm.where(data["pm_valid"].fillna(False).astype(bool))
    # Forward-fill only short gaps, and only for features. Labels never use filled values.
    pm_filled = pm.ffill(limit=2)

    out = pd.DataFrame(index=data.index)
    out["pm_now"] = pm_filled
    for lag in PM_LAGS:
        out[f"pm_lag_{lag}"] = pm_filled.shift(lag)

    for window in ROLL_WINDOWS:
        rolling = pm_filled.rolling(window, min_periods=1)
        out[f"pm_roll_mean_{window}"] = rolling.mean()
        out[f"pm_roll_std_{window}"] = rolling.std()
        out[f"pm_roll_min_{window}"] = rolling.min()
        out[f"pm_roll_max_{window}"] = rolling.max()
        out[f"pm_roll_median_{window}"] = rolling.median()
        out[f"pm_roll_coverage_{window}"] = pm.notna().rolling(window, min_periods=1).mean()

    for delta in (1, 5, 15):
        out[f"pm_diff_{delta}"] = pm_filled.diff(delta)
    out["pm_slope_5"] = (pm_filled - pm_filled.shift(5)) / 5.0
    out["pm_slope_15"] = (pm_filled - pm_filled.shift(15)) / 15.0
    out["missing_pm"] = pm.isna()
    out["pm_acceleration_5"] = out["pm_slope_5"] - out["pm_slope_5"].shift(5)

    for column in ("co2", "temperature", "humidity"):
        values = pd.to_numeric(data[column], errors="coerce").astype(float)
        out[f"{column}_now"] = values
        out[f"{column}_mean_5"] = values.rolling(5, min_periods=1).mean()
        out[f"{column}_mean_15"] = values.rolling(15, min_periods=1).mean()
        out[f"missing_{column}"] = values.isna()
    co2 = pd.to_numeric(data["co2"], errors="coerce").astype(float)
    out["co2_diff_5"] = co2.diff(5)

    # Time of day is known in advance and has no leakage.
    local = data.index.tz_convert("Asia/Ho_Chi_Minh")
    out["hour_sin"] = np.sin(2 * np.pi * local.hour / 24.0)
    out["hour_cos"] = np.cos(2 * np.pi * local.hour / 24.0)

    for column in ("fan_on", "fan_level", "fan_mode"):
        if column in data.columns:
            values = pd.to_numeric(data[column], errors="coerce").astype(float)
        else:
            values = pd.Series(np.nan, index=data.index, dtype=float)
        if column == "fan_on":
            for window in FAN_WINDOWS:
                out[f"fan_on_ratio_{window}"] = values.rolling(window, min_periods=1).mean()
        if column == "fan_level":
            out["fan_level_mean_15"] = values.rolling(15, min_periods=1).mean()
        if column == "fan_mode":
            out["fan_mode_now"] = values
        out[f"{column}_now"] = values

    return out


def feature_columns(features: pd.DataFrame) -> list[str]:
    return list(features.columns)


def ready_origins(features: pd.DataFrame) -> pd.DataFrame:
    """Rows where every mandatory PM feature is present."""
    if features.empty:
        return features
    usable = features.dropna(subset=REQUIRED_AT_ORIGIN)
    usable = usable.loc[~usable["missing_pm"].astype(bool)]
    return usable


def latest_origin(features: pd.DataFrame, now: pd.Timestamp, max_age_minutes: int = 5) -> pd.Timestamp | None:
    usable = ready_origins(features.loc[features.index <= now])
    usable = usable.loc[~usable["missing_pm"].astype(bool)]
    if usable.empty:
        return None
    origin = usable.index.max()
    if (now - origin) > pd.Timedelta(minutes=max_age_minutes):
        return None
    return origin
