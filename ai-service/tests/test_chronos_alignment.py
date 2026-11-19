"""Regression tests for the grid-alignment bug: a 5.02 s cadence must not halve the context.

The old code placed a sample only when it was within half a step (2.5 s) of a grid point. A device
that reports every 5.02 s drifts 0.02 s per packet, so about half its samples fell outside that
window and the context reported ~50 % coverage — below the 80 % readiness floor, so no forecast was
issued even though the device was healthy.
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
    _align_to_grid,
    build_context,
    ensure_ready,
    longest_gap_steps,
)

NOW = datetime(2026, 11, 18, 12, 0, 0, tzinfo=timezone.utc)


def rows_with_cadence(seconds: float, count: int = CONTEXT_STEPS,
                      start_offset: float = 0.0) -> list[dict]:
    """A perfectly healthy stream whose packets are `seconds` apart, ending at NOW."""
    rows = []
    for index in range(count):
        at = NOW - timedelta(seconds=seconds * (count - index)) + timedelta(seconds=start_offset)
        rows.append(
            {
                "recorded_at": at.isoformat(),
                "received_at": (at + timedelta(seconds=0.3)).isoformat(),
                "pm25": 30.0,
                "co2": 700.0,
                "temperature": 24.0,
                "humidity": 55.0,
            }
        )
    return rows


def test_exact_cadence_is_full_coverage():
    context = build_context(rows_with_cadence(5.0), now=pd.Timestamp(NOW))
    assert context.coverage == 1.0


@pytest.mark.parametrize("cadence", [5.01, 5.02, 5.05, 5.2, 4.98])
def test_slightly_drifting_cadence_stays_above_readiness(cadence: float):
    """The regression: these used to land near 50 % coverage."""
    context = build_context(rows_with_cadence(cadence), now=pd.Timestamp(NOW),
                            require_fresh=False)
    assert context.coverage >= MIN_CONTEXT_COVERAGE, (
        f"cadence {cadence}s produced {context.coverage:.0%} coverage"
    )
    ensure_ready(context)


def test_phase_offset_does_not_lose_samples():
    """A stream that starts half a step off the grid is still a complete stream."""
    context = build_context(rows_with_cadence(5.0, start_offset=2.4), now=pd.Timestamp(NOW),
                            require_fresh=False)
    assert context.coverage >= growth_floor()


def test_real_gap_is_still_a_gap():
    """The fix must not turn missing data into filled data."""
    rows = rows_with_cadence(5.0)
    del rows[200:500]
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)
    assert context.coverage < MIN_CONTEXT_COVERAGE
    with pytest.raises(NotReady):
        ensure_ready(context)


def test_a_ten_minute_hole_is_refused_even_when_coverage_is_high():
    """
    Coverage alone lets an outage hide: 120 missing steps out of 720 is 83 %, which used to pass.

    The gap rule is what actually protects the forecast — a curve fitted across a ten-minute hole is
    a guess wearing a confidence band.
    """
    rows = rows_with_cadence(5.0)
    del rows[300:420]
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)

    assert context.coverage >= MIN_CONTEXT_COVERAGE      # coverage alone would accept it
    with pytest.raises(NotReady, match="mất dữ liệu"):
        ensure_ready(context)


def test_a_short_hole_is_accepted():
    rows = rows_with_cadence(5.0)
    del rows[300:350]                                    # 50 steps = 4.2 minutes
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)
    ensure_ready(context)


def test_longest_gap_steps_counts_the_hole():
    values = np.array([1.0, np.nan, np.nan, np.nan, 2.0, np.nan, 3.0])
    assert longest_gap_steps(values) == 3
    assert longest_gap_steps(np.array([1.0, 2.0])) == 0


def test_short_history_is_not_a_hole():
    """
    Cells before the first sample are short history, not a gap.

    A device that started 30 minutes ago has an 80-minute empty prefix in a 110-minute window;
    reporting that as a "hole" told the operator something false about a perfect stream.
    """
    values = np.concatenate([np.full(400, np.nan), np.full(320, 25.0)])
    assert longest_gap_steps(values) == 0

    # A real hole inside the observed span still counts.
    values[500:560] = np.nan
    assert longest_gap_steps(values) == 60

    # Nothing observed at all is also not a hole; coverage reports that separately.
    assert longest_gap_steps(np.array([np.nan, np.nan])) == 0


def test_align_keeps_newest_sample_per_cell():
    start = pd.Timestamp("2026-11-18T11:00:00Z")
    step = pd.Timedelta(seconds=5)
    index = pd.DatetimeIndex([
        start + pd.Timedelta(seconds=1),   # nearest grid point 0
        start + pd.Timedelta(seconds=2),   # same point, newer -> wins
        start + pd.Timedelta(seconds=11),  # nearest grid point 2
    ])
    series = pd.Series([10.0, 20.0, 30.0], index=index)
    values = _align_to_grid(series, start, step, 4)
    assert values[0] == 20.0
    assert np.isnan(values[1])
    assert values[2] == 30.0


def test_align_ignores_samples_after_the_grid():
    start = pd.Timestamp("2026-11-18T11:00:00Z")
    step = pd.Timedelta(seconds=5)
    series = pd.Series([1.0, 2.0],
                       index=pd.DatetimeIndex([start + step, start + step * 9]))
    values = _align_to_grid(series, start, step, 4)
    assert values[1] == 1.0
    assert np.isfinite(values).sum() == 1


def test_align_tolerates_half_a_step_of_phase():
    start = pd.Timestamp("2026-11-18T11:00:00Z")
    step = pd.Timedelta(seconds=5)
    series = pd.Series([7.0], index=pd.DatetimeIndex([start + pd.Timedelta(seconds=2.4)]))
    values = _align_to_grid(series, start, step, 3)
    assert values[0] == 7.0


def growth_floor() -> float:
    """A phase-shifted full stream loses at most one cell at each end."""
    return (CONTEXT_STEPS - 2) / CONTEXT_STEPS
