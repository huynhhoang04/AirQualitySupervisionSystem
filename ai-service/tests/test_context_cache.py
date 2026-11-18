"""The incremental context cache must advance without reloading or inventing rows."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.backend import BackendError
from app.context_cache import ContextCache

NOW = datetime.now(timezone.utc)


def row(row_id: int, seconds_ago: int, pm: float = 25.0) -> dict[str, Any]:
    at = NOW - timedelta(seconds=seconds_ago)
    return {
        "id": row_id,
        "recorded_at": at.isoformat(),
        "received_at": at.isoformat(),
        "pm25": pm,
        "co2": 700.0,
    }


class FakeClient:
    """Replays a fixed list of rows and answers incremental reads from it."""

    def __init__(self, rows: list[dict[str, Any]], fail_incremental: bool = False):
        self.rows = sorted(rows, key=lambda item: item["id"])
        self.fail_incremental = fail_incremental
        self.full_loads = 0
        self.incremental_calls = 0

    def recent(self, device_id: str, minutes: int) -> list[dict[str, Any]]:
        self.full_loads += 1
        return list(self.rows)

    def recent_actuator(self, device_id: str, minutes: int) -> list[dict[str, Any]]:
        return []

    def samples_after(self, device_id: str, after_id: int, limit: int = 500) -> dict[str, Any]:
        self.incremental_calls += 1
        if self.fail_incremental:
            raise BackendError("GET /samples -> 503: unavailable")
        items = [item for item in self.rows if item["id"] > after_id][:limit]
        return {
            "items": items,
            "next_after_id": items[-1]["id"] if items else None,
            "high_water_id": self.rows[-1]["id"] if self.rows else 0,
        }

    def actuator_reports_after(self, device_id: str, after_id: int, limit: int = 200) -> dict[str, Any]:
        return {"items": [], "next_after_id": None, "high_water_id": 0}


def test_first_read_is_a_full_load():
    client = FakeClient([row(1, 60), row(2, 5)])
    cache = ContextCache(client)
    cache.rows("dev-1")
    assert client.full_loads == 1
    assert client.incremental_calls == 0


def test_second_read_is_incremental_only():
    client = FakeClient([row(1, 60), row(2, 5)])
    cache = ContextCache(client)
    cache.rows("dev-1")
    client.rows.append(row(3, 1))
    samples, _ = cache.rows("dev-1")
    assert client.full_loads == 1
    assert client.incremental_calls >= 1
    assert [item["id"] for item in samples][-1] == 3


def test_missing_rows_are_not_invented():
    client = FakeClient([row(1, 60)])
    cache = ContextCache(client)
    cache.rows("dev-1")
    samples, _ = cache.rows("dev-1")
    assert [item["id"] for item in samples] == [1]


def test_incremental_failure_falls_back_to_one_full_reload():
    client = FakeClient([row(1, 60)], fail_incremental=True)
    cache = ContextCache(client)
    cache.rows("dev-1")
    cache.rows("dev-1")
    assert client.full_loads == 2   # the cache reloaded instead of guessing
    assert cache.stats("dev-1")["reloads"] == 2


def test_window_trim_keeps_only_recent_rows():
    old = row(1, 4 * 3600)
    fresh = row(2, 30)
    client = FakeClient([old, fresh])
    cache = ContextCache(client, window_minutes=90)
    samples, _ = cache.rows("dev-1")
    assert [item["id"] for item in samples] == [2]


def test_stats_report_a_cold_device():
    cache = ContextCache(FakeClient([]))
    assert cache.stats("never-seen")["status"] == "cold"


@pytest.mark.parametrize("after_id", [0, 1, 2])
def test_cursor_advances_and_never_duplicates(after_id: int):
    rows = [row(index, 60 - index) for index in range(1, 6)]
    client = FakeClient(rows)
    cache = ContextCache(client)
    state = cache.full_reload("dev-1")
    # Rewinding the cursor is a "catch-up" read: the cache keeps its window and adds what it missed.
    state.after_id = after_id
    samples, _ = cache.rows("dev-1")
    ids = [item["id"] for item in samples]
    assert ids == sorted(ids)
    assert state.after_id == 5
    assert len(ids) == len(set(ids))
