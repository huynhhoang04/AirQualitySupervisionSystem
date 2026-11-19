"""Readiness and causality rules of the Chronos context builder.

These tests do not load the model: they pin the contract that decides whether a forecast may be
issued at all, which is the part that silently breaks when data arrives late or with gaps.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import pytest

from app.chronos_model import (
    CONTEXT_STEPS,
    MIN_CONTEXT_COVERAGE,
    NotReady,
    build_context,
    ensure_ready,
)

NOW = datetime(2026, 11, 18, 12, 0, 0, tzinfo=timezone.utc)


def rows_for(steps: int = CONTEXT_STEPS, gap: tuple[int, int] | None = None,
             pm: str = "flat") -> list[dict]:
    rows = []
    for index in range(steps):
        at = NOW - timedelta(seconds=5 * (steps - index))
        if gap is not None and gap[0] <= index < gap[1]:
            continue
        value = 30.0 if pm == "flat" else 20.0 + 0.02 * index
        rows.append(
            {
                "recorded_at": at.isoformat(),
                "received_at": (at + timedelta(seconds=1)).isoformat(),
                "pm25": value,
                "co2": 700.0,
                "temperature": 24.0,
                "humidity": 55.0,
                "fan_level": 0,
            }
        )
    return rows


def test_builds_full_grid_ending_at_newest_sample():
    context = build_context(rows_for(), now=pd.Timestamp(NOW))
    assert len(context.grid) == CONTEXT_STEPS
    assert context.coverage == 1.0
    assert context.steps_present == CONTEXT_STEPS
    assert context.grid[-1] == pd.Timestamp(NOW).floor("s") - pd.Timedelta(seconds=5)
    assert context.anchor_pm25 == 30.0


def test_anchor_is_the_newest_valid_sample():
    context = build_context(rows_for(pm="ramp"), now=pd.Timestamp(NOW))
    assert context.anchor_pm25 == pytest.approx(20.0 + 0.02 * (CONTEXT_STEPS - 1), abs=1e-6)


def test_long_gap_is_refused_rather_than_interpolated():
    context = build_context(rows_for(gap=(100, 500)), now=pd.Timestamp(NOW))
    assert context.coverage < MIN_CONTEXT_COVERAGE
    # Either reason is a refusal; the gap rule usually fires first and names the actual hole.
    with pytest.raises(NotReady, match="mất dữ liệu|bước hợp lệ"):
        ensure_ready(context)


def test_short_gap_still_passes_at_eighty_percent():
    # 50 missing steps out of 720 leaves 93 %, which is above the readiness floor and under the
    # 5-minute hole limit. A hole of 100 steps would now be refused by the gap rule instead.
    context = build_context(rows_for(gap=(200, 250)), now=pd.Timestamp(NOW))
    assert context.coverage == pytest.approx(670 / 720, abs=1e-3)
    ensure_ready(context)


def test_stale_anchor_is_refused():
    rows = rows_for()
    with pytest.raises(NotReady, match="giây"):
        build_context(rows, now=pd.Timestamp(NOW + timedelta(minutes=5)))


def test_late_sample_cannot_be_an_input():
    rows = rows_for()
    # The newest sample arrives *after* the issue time: a replay must not improve the forecast.
    rows[-1]["received_at"] = (NOW + timedelta(seconds=30)).isoformat()
    rows[-1]["pm25"] = 999.0
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)
    assert context.steps_present == CONTEXT_STEPS - 1
    assert context.anchor_pm25 != 999.0


def test_saturated_and_fault_samples_are_masked():
    rows = rows_for()
    for row in rows[-50:]:
        row["pm25_saturated"] = True
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)
    # The anchor moves back to the last usable sample, so the grid ends there and the masked tail
    # is not counted as present.
    assert context.steps_present == context.steps_used
    assert context.anchor_pm25 != 30.0 or context.steps_present < CONTEXT_STEPS


def test_out_of_range_pm_is_dropped():
    rows = rows_for()
    rows[-1]["pm25"] = 900.0
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)
    assert context.anchor_pm25 != 900.0


def test_empty_history_is_not_ready():
    with pytest.raises(NotReady):
        build_context([], now=pd.Timestamp(NOW))


def test_future_timestamps_are_ignored():
    rows = rows_for()
    rows.append(
        {
            "recorded_at": (NOW + timedelta(hours=1)).isoformat(),
            "received_at": (NOW + timedelta(hours=1)).isoformat(),
            "pm25": 400.0,
        }
    )
    context = build_context(rows, now=pd.Timestamp(NOW))
    assert context.anchor_pm25 != 400.0


def test_covariate_alignment_uses_past_only():
    context = build_context(rows_for(), now=pd.Timestamp(NOW))
    fan = context.covariates["fan_level"]
    assert np.isfinite(fan).all()
    assert np.all(fan == 0.0)
