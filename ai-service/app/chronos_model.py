"""Chronos-2 adapter: one 5-second grid, one 30-minute path, one median line.

The interface matches the plan (docs/upgrade-plan/07 §4): context of 720 steps (60 min) and a
forecast of 360 steps (+5 s .. +30 min). Everything is expressed in *steps of 5 seconds*, so a
change of cadence is a change of constant, not of the algorithm.

Design rules:

* only past values become covariates — no future fan, no future CO2, no future ventilation;
* gaps stay gaps: a step without a sample is NaN in the context and the readiness rule decides
  whether the model may run at all;
* the returned path is the model's median, clipped to the sensor domain, with the anchor at
  index 0 so the chart can connect measured history to forecast without inventing a value.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Sequence

import numpy as np
import pandas as pd

MODEL_ID = "amazon/chronos-2"
STEP_SECONDS = 5
CONTEXT_STEPS = 720
PREDICTION_STEPS = 360

# Readiness: 80 % of the window must carry a usable sample, and the newest one must be fresh.
MIN_CONTEXT_COVERAGE = 0.8
MAX_ANCHOR_AGE_SECONDS = 15
# A single hole longer than this is disqualifying even when the rest of the window is full:
# coverage alone lets a 10-minute outage hide inside 83 %, and a curve fitted across it is a guess.
MAX_GAP_STEPS = 60

PM_MIN = 0.0
PM_MAX = 500.0

QUANTILE_LEVELS = (0.1, 0.5, 0.9)


class NotReady(Exception):
    """The context is not good enough to issue a forecast; the chart keeps the measured line."""


@dataclass
class ChronosContext:
    """The exact grid handed to the model, plus what the UI needs to describe it."""

    grid: pd.DatetimeIndex
    target: np.ndarray            # PM2.5, NaN where missing
    covariates: dict[str, np.ndarray]
    anchor_at: datetime
    anchor_pm25: float
    coverage: float               # share of the 720 steps with a PM sample
    steps_present: int
    anchor_age_seconds: float


def _number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _instant(value: Any) -> pd.Timestamp | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return pd.Timestamp(datetime.fromtimestamp(float(value), tz=timezone.utc))
    text = str(value).strip()
    if not text:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = pd.Timestamp(text)
    except (ValueError, TypeError):
        return None
    return parsed.tz_convert("UTC") if parsed.tzinfo else parsed.tz_localize("UTC")


def _align_to_grid(series: pd.Series, start: pd.Timestamp, step: pd.Timedelta,
                   count: int) -> np.ndarray:
    """
    Snap each sample to its nearest grid point, keeping the newest sample per point.

    Grid point `i` is `start + i·step`, and a sample belongs to the point it is closest to (within
    ±half a step). This is what makes a real device work: packets every 5.02 s drift by 0.02 s each
    but stay within half a step of a grid point for hundreds of packets, so the window stays full.
    An earlier version required a sample within half a step of each grid point *from the same
    direction* and silently dropped about half of them.

    A cell with no sample stays NaN: this never interpolates across missing data.
    """
    series = series.dropna()
    if series.empty:
        return np.full(count, np.nan)
    offsets = ((series.index - start) / step).to_numpy(dtype=float)
    indices = np.rint(offsets).astype("int64")
    values = np.full(count, np.nan)
    inside = (indices >= 0) & (indices < count)
    # Sorted ascending, so a later sample in the same cell overwrites the earlier one.
    values[indices[inside]] = series.to_numpy(dtype=float)[inside]
    return values


def build_context(rows: Sequence[dict[str, Any]], *, now: pd.Timestamp | None = None,
                  interval_seconds: int = STEP_SECONDS,
                  context_steps: int = CONTEXT_STEPS,
                  require_fresh: bool = True) -> ChronosContext:
    """
    Put the recent samples on a regular 5-second grid ending at the newest usable sample.

    A sample is usable when it was measured and received before "now", has a PM value inside the
    sensor domain, and is not flagged saturated or faulty. Each grid cell takes the newest usable
    sample inside it, so the anchor is a real measurement, never an average created here.
    """
    if not rows:
        raise NotReady("Chưa có mẫu đo nào để dựng context.")

    issued = now or pd.Timestamp(datetime.now(timezone.utc))
    records = []
    for row in rows:
        recorded = _instant(row.get("recorded_at") or row.get("timestamp"))
        if recorded is None or recorded > issued:
            continue
        received = _instant(row.get("received_at")) or recorded
        if received > issued:
            continue   # a late replay cannot be an input for a forecast issued earlier
        usable = (not bool(row.get("pm25_saturated")) and not bool(row.get("dust_fault")))
        records.append(
            {
                "at": recorded,
                "pm25": _number(row.get("pm25")) if usable else None,
                "co2": _number(row.get("co2")),
                "temperature": _number(row.get("temperature")),
                "humidity": _number(row.get("humidity")),
                "fan_level": _number(row.get("fan_level")),
            }
        )
    frame = pd.DataFrame(records).dropna(subset=["at"]).sort_values("at")
    frame = frame.drop_duplicates(subset=["at"], keep="last")
    frame = frame[(frame["pm25"].isna()) | frame["pm25"].between(PM_MIN, PM_MAX)]
    if frame.empty:
        raise NotReady("Không có mẫu đo hợp lệ trong cửa sổ.")

    valid = frame.dropna(subset=["pm25"])
    if valid.empty:
        raise NotReady("Chưa có mẫu PM hợp lệ; kiểm tra cảm biến bụi.")

    step = pd.Timedelta(seconds=interval_seconds)
    anchor_at = valid["at"].iloc[-1]
    anchor_pm25 = float(valid["pm25"].iloc[-1])
    age = (issued - anchor_at).total_seconds()
    if require_fresh and age > MAX_ANCHOR_AGE_SECONDS:
        raise NotReady(f"Mẫu mới nhất đã {age:.0f} giây; cần mới hơn {MAX_ANCHOR_AGE_SECONDS} giây.")

    grid = pd.date_range(anchor_at - step * (context_steps - 1), anchor_at, freq=step)
    start = grid[0]
    indexed = frame.set_index("at")

    target = _align_to_grid(indexed["pm25"], start, step, context_steps)
    steps_present = int(np.isfinite(target).sum())
    coverage = steps_present / context_steps

    return ChronosContext(
        grid=grid,
        target=target,
        covariates={
            "co2": _align_to_grid(indexed["co2"], start, step, context_steps),
            "temperature": _align_to_grid(indexed["temperature"], start, step, context_steps),
            "humidity": _align_to_grid(indexed["humidity"], start, step, context_steps),
            "fan_level": _align_to_grid(indexed["fan_level"], start, step, context_steps),
        },
        anchor_at=anchor_at.to_pydatetime(),
        anchor_pm25=anchor_pm25,
        coverage=coverage,
        steps_present=steps_present,
        anchor_age_seconds=age,
    )


def longest_gap_steps(values: np.ndarray) -> int:
    """Longest run of missing cells in a grid array, in steps."""
    longest = current = 0
    for present in np.isfinite(values):
        current = 0 if present else current + 1
        longest = max(longest, current)
    return longest


def ensure_ready(context: ChronosContext, *, min_coverage: float = MIN_CONTEXT_COVERAGE,
                 max_gap_steps: int = MAX_GAP_STEPS) -> None:
    if context.coverage < min_coverage:
        raise NotReady(
            f"Context chỉ có {context.coverage:.0%} bước hợp lệ; cần ≥ {min_coverage:.0%} "
            f"({context.steps_present}/{CONTEXT_STEPS} bước 5 giây)."
        )
    gap = longest_gap_steps(context.target)
    if gap > max_gap_steps:
        raise NotReady(
            f"Có khoảng mất dữ liệu {gap * STEP_SECONDS / 60:.1f} phút trong context; "
            f"tối đa {max_gap_steps * STEP_SECONDS / 60:.0f} phút."
        )
    if not math.isfinite(context.anchor_pm25):
        raise NotReady("Mẫu nối không có PM hợp lệ.")


def _at_anchor(values: np.ndarray | None) -> float | None:
    """Last finite value of a covariate, or None. Never fills a missing reading."""
    if values is None or len(values) == 0:
        return None
    finite = values[np.isfinite(values)]
    return round(float(finite[-1]), 1) if len(finite) else None


def _pad_or_trim(values: np.ndarray, length: int) -> np.ndarray:
    if len(values) >= length:
        return values[-length:]
    return np.concatenate([np.full(length - len(values), np.nan), values])


def to_model_input(context: ChronosContext) -> list[dict[str, Any]]:
    """
    One sample in Chronos-2's documented shape.

    Past covariates are passed exactly as observed; no key is placed in `future_covariates`,
    because the room's future fan state and future CO2 are unknown at issue time.
    """
    target = _pad_or_trim(context.target.astype(np.float32), CONTEXT_STEPS)
    covariates = {
        name: _pad_or_trim(values.astype(np.float32), CONTEXT_STEPS)
        for name, values in context.covariates.items()
        if np.isfinite(values).any()
    }
    sample: dict[str, Any] = {"target": target.reshape(1, -1)}
    if covariates:
        sample["past_covariates"] = covariates
    return [sample]


class ChronosForecaster:
    """Loaded pipeline + the one call the worker needs."""

    def __init__(self, pipeline: Any, *, model_version: str, device: str | None = None):
        self.pipeline = pipeline
        self.model_version = model_version
        self.device = device

    @classmethod
    def load(cls, model_path: str = MODEL_ID, *, device: str = "cuda",
             model_version: str | None = None) -> "ChronosForecaster":
        from chronos import Chronos2Pipeline

        pipeline = Chronos2Pipeline.from_pretrained(model_path, device_map=device)
        return cls(pipeline, model_version=model_version or MODEL_ID, device=device)

    def forecast(self, context: ChronosContext, *, steps: int = PREDICTION_STEPS,
                 quantiles: Sequence[float] = QUANTILE_LEVELS) -> dict[str, Any]:
        """
        Return the median path plus the 10/90 band. Index i corresponds to anchor + (i+1) steps:
        the anchor itself is the measured value and is not part of the forecast.
        """
        ensure_ready(context)
        inputs = to_model_input(context)
        # `predict` returns the full quantile tensor; `predict_quantiles` selects levels and does
        # interpolation, so one call is enough for both the median and the band.
        raw = self.pipeline.predict(
            inputs,
            prediction_length=steps,
            context_length=CONTEXT_STEPS,
        )
        tensor = raw[0]
        values = tensor.detach().cpu().numpy() if hasattr(tensor, "detach") else np.asarray(tensor)
        # Chronos-2 returns (n_variates, n_quantiles, prediction_length).
        while values.ndim > 2:
            values = values[0]
        available = list(getattr(self.pipeline, "quantiles", [0.5]))
        chosen = {}
        for level in quantiles:
            index = min(range(len(available)), key=lambda i: abs(available[i] - level))
            chosen[level] = np.clip(values[index], PM_MIN, PM_MAX)

        median = chosen[min(quantiles, key=lambda q: abs(q - 0.5))]
        step = pd.Timedelta(seconds=STEP_SECONDS)
        targets = [context.anchor_at + step * (i + 1) for i in range(steps)]
        return {
            "anchor_at": context.anchor_at,
            "anchor_pm25": context.anchor_pm25,
            "step_seconds": STEP_SECONDS,
            "values": [round(float(v), 3) for v in median],
            "q10": [round(float(v), 3) for v in chosen[min(quantiles)]],
            "q90": [round(float(v), 3) for v in chosen[max(quantiles)]],
            "targets": targets,
            "coverage": context.coverage,
            "steps_present": context.steps_present,
            "model_version": self.model_version,
        }

    def forecast_context(self, context: ChronosContext) -> dict[str, Any]:
        """The shape the backend stores: three evaluation points plus the full path."""
        result = self.forecast(context)
        step = pd.Timedelta(seconds=STEP_SECONDS)
        points = []
        for horizon in (5, 15, 30):
            index = int(horizon * 60 / STEP_SECONDS) - 1
            points.append(
                {
                    "horizon_minutes": horizon,
                    "target_at": (context.anchor_at + step * (index + 1))
                    .replace(tzinfo=timezone.utc)
                    .isoformat(),
                    "pm25": result["values"][index],
                }
            )
        return {
            "points": points,
            "path": {
                "anchor_at": context.anchor_at.replace(tzinfo=timezone.utc).isoformat(),
                "anchor_pm25": round(context.anchor_pm25, 3),
                "step_seconds": STEP_SECONDS,
                "values": result["values"],
                "q10": result["q10"],
                "q90": result["q90"],
            },
            "data_quality": {
                "model_kind": "chronos",
                "model_version": result["model_version"],
                "context_steps": CONTEXT_STEPS,
                "prediction_steps": PREDICTION_STEPS,
                "context_coverage": round(context.coverage, 3),
                "steps_present": context.steps_present,
                "anchor_age_seconds": round(context.anchor_age_seconds, 2),
                "pm_at_origin": round(context.anchor_pm25, 3),
                # The CO2 reading at the anchor, for the ventilation advisory. NaN when the SCD40
                # had not produced a reading yet; it is not filled with a plausible number.
                "co2_at_origin": _at_anchor(context.covariates.get("co2")),
            },
        }
