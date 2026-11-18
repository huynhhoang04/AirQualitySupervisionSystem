"""The fan covariate must reach the model, and a Chronos run must still produce an advisory.

Both bugs had the same shape: the data existed downstream, but the Chronos path never used it, so
the model silently saw a NaN column and the UI silently lost its one-line warning.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import numpy as np
import pytest

from app.chronos_model import CONTEXT_STEPS, build_context
from app.chronos_runner import ChronosRunner
from app.config import Settings
from app.context_cache import ContextCache

NOW = datetime.now(timezone.utc)


def sample(seconds_ago: int, pm: float = 30.0) -> dict[str, Any]:
    at = NOW - timedelta(seconds=seconds_ago)
    return {
        "id": seconds_ago,
        "recorded_at": at.isoformat(),
        "received_at": at.isoformat(),
        "pm25": pm,
        "co2": 700.0,
        "temperature": 24.0,
        "humidity": 55.0,
    }


def report(seconds_ago: int, level: int) -> dict[str, Any]:
    at = NOW - timedelta(seconds=seconds_ago)
    return {"id": seconds_ago, "received_at": at.isoformat(), "level": level, "state": "ON"}


class FakeClient:
    def __init__(self, samples: list[dict], actuator: list[dict]):
        self.samples = samples
        self.actuator = actuator

    def recent(self, device_id: str, minutes: int) -> list[dict]:
        return list(self.samples)

    def recent_actuator(self, device_id: str, minutes: int) -> list[dict]:
        return list(self.actuator)

    def samples_after(self, device_id: str, after_id: int, limit: int = 500) -> dict:
        items = [row for row in self.samples if row["id"] > after_id][:limit]
        return {"items": items, "next_after_id": items[-1]["id"] if items else None,
                "high_water_id": max((row["id"] for row in self.samples), default=0)}

    def actuator_reports_after(self, device_id: str, after_id: int, limit: int = 200) -> dict:
        return {"items": [], "next_after_id": None, "high_water_id": 0}


def test_fan_level_reaches_the_context():
    """720 actuator reports at level 3 must produce a finite fan column, not NaN."""
    samples = [sample(5 * index) for index in range(CONTEXT_STEPS, 0, -1)]
    actuator = [report(5 * index, 3) for index in range(CONTEXT_STEPS, 0, -1)]
    cache = ContextCache(FakeClient(samples, actuator))

    enriched, _ = cache.rows_with_fan("dev-1")
    context = build_context(enriched, now=None, require_fresh=False)
    fan = context.covariates["fan_level"]

    assert np.isfinite(fan).any(), "fan_level was all NaN — the covariate was dropped"
    assert np.nanmax(fan) == 3.0
    assert np.isfinite(fan).sum() >= CONTEXT_STEPS - 2


def test_fan_join_is_as_of_receive_time():
    """A report received after a sample must not describe it."""
    samples = [sample(60), sample(30)]
    actuator = [report(10, 3)]   # only known 10 s ago
    cache = ContextCache(FakeClient(samples, actuator))

    enriched, _ = cache.rows_with_fan("dev-1")

    assert enriched[0].get("fan_level") is None
    assert enriched[1].get("fan_level") is None


def test_no_actuator_reports_leaves_fan_unknown():
    samples = [sample(30)]
    cache = ContextCache(FakeClient(samples, []))
    enriched, _ = cache.rows_with_fan("dev-1")
    assert enriched[0].get("fan_level") is None


def test_payload_carries_recommendations():
    """The room screen needs the advisory; the Chronos branch used to omit it."""
    settings = Settings.from_env()
    runner = ChronosRunner(settings, FakeClient([], []))
    forecast = {
        "points": [
            {"horizon_minutes": 5, "target_at": NOW.isoformat(), "pm25": 40.0},
            {"horizon_minutes": 15, "target_at": NOW.isoformat(), "pm25": 45.0},
            {"horizon_minutes": 30, "target_at": NOW.isoformat(), "pm25": 42.0},
        ],
        "path": {"anchor_at": NOW.isoformat(), "anchor_pm25": 28.0},
        "data_quality": {
            "model_kind": "chronos",
            "model_version": "chronos-2-test",
            "pm_at_origin": 28.0,
            "co2_at_origin": 1200.0,
        },
    }

    payload = runner._payload("dev-1", {"device_id": "dev-1"}, forecast,
                              {"space": {"threshold_pm25": 35.0}})

    items = payload["data_quality"]["recommendations"]
    assert items, "no advisory produced for a forecast that breaches 35"
    assert any(item["kind"] == "forecast_breach" for item in items)
    assert any(item["kind"] == "ventilation" for item in items)


def test_payload_without_threshold_has_no_advice():
    settings = Settings.from_env()
    runner = ChronosRunner(settings, FakeClient([], []))
    forecast = {
        "points": [],
        "path": {"anchor_at": NOW.isoformat(), "anchor_pm25": 20.0},
        "data_quality": {"model_kind": "chronos", "model_version": "x", "pm_at_origin": 20.0},
    }

    payload = runner._payload("dev-1", {"device_id": "dev-1"}, forecast, {"space": {}})

    assert payload["data_quality"]["recommendations"] == []
