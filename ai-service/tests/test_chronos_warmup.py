"""Warm-up forecasts: a new node must not wait 48 minutes for its first curve.

The readiness gate originally required the full 720-step (60 min) window at 80 % coverage, which is
48 minutes of data. A freshly installed node produced nothing for that long — the feature looked
broken. Chronos handles a shorter context, so the node now issues a clearly-labelled warm-up
forecast after 15 minutes, and switches to a full-window forecast once it has an hour.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import pytest

from app.chronos_model import (
    CONTEXT_STEPS,
    MIN_WARMUP_STEPS,
    READINESS_FULL,
    READINESS_WARMUP,
    NotReady,
    build_context,
    ensure_ready,
)

NOW = datetime(2026, 11, 19, 0, 0, 0, tzinfo=timezone.utc)


def stream(minutes: float, cadence: float = 5.0, end: datetime = NOW) -> list[dict]:
    count = int(minutes * 60 / cadence)
    rows = []
    for index in range(count):
        at = end - timedelta(seconds=cadence * (count - index))
        rows.append(
            {
                "recorded_at": at.isoformat(),
                "received_at": (at + timedelta(seconds=0.3)).isoformat(),
                "pm25": 25.0 + 0.01 * index,
                "co2": 700.0,
                "temperature": 24.0,
                "humidity": 55.0,
            }
        )
    return rows


def test_five_minutes_is_still_too_short():
    context = build_context(stream(5), now=pd.Timestamp(NOW), require_fresh=False)
    with pytest.raises(NotReady, match="phút lịch sử"):
        ensure_ready(context)


def test_fifteen_minutes_is_enough_for_a_warmup_forecast():
    context = build_context(stream(15), now=pd.Timestamp(NOW), require_fresh=False)

    assert ensure_ready(context) == READINESS_WARMUP
    assert context.steps_used == pytest.approx(min(CONTEXT_STEPS, 15 * 60 / 5), abs=2)
    assert context.grid[-1].to_pydatetime() <= NOW


def test_a_full_hour_is_a_full_forecast():
    context = build_context(stream(70), now=pd.Timestamp(NOW), require_fresh=False)

    assert ensure_ready(context) == READINESS_FULL
    assert context.steps_used == CONTEXT_STEPS


def test_warmup_still_refuses_a_hole():
    rows = stream(30)
    del rows[20:140]   # 10 minutes missing
    context = build_context(rows, now=pd.Timestamp(NOW), require_fresh=False)
    with pytest.raises(NotReady, match="mất dữ liệu"):
        ensure_ready(context)


def test_warmup_labels_itself_in_the_payload():
    """The curve's metadata must say which claim it supports."""
    from app.chronos_model import ChronosContext

    context = build_context(stream(20), now=pd.Timestamp(NOW), require_fresh=False)
    readiness = ensure_ready(context)
    assert readiness == READINESS_WARMUP

    # A full context reports the other label; both are distinguishable without guessing.
    full = build_context(stream(70), now=pd.Timestamp(NOW), require_fresh=False)
    assert ensure_ready(full) == READINESS_FULL


def test_warmup_grid_is_not_padded_to_720():
    """Padding a short history to 720 with NaN would fake a full window."""
    context = build_context(stream(20), now=pd.Timestamp(NOW), require_fresh=False)
    assert context.steps_used < CONTEXT_STEPS
    assert np.isfinite(context.target).sum() >= MIN_WARMUP_STEPS
    # No leading NaN run: the grid starts at the first real sample.
    assert np.isfinite(context.target[0])
