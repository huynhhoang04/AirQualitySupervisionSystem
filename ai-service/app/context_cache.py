"""Incremental context cache for the local worker.

The worker used to reload 90 minutes of history on every inference tick. At a 5-second cadence that
is 720 rows per packet for no benefit: the rows are almost the same. This cache loads the window
once and then asks the backend only for rows it has not seen (io_contract §14.4).

Rules that matter:

* the cursor is a row id, not a timestamp — two samples can share a millisecond;
* a gap the cache cannot cover (worker restart, long outage, server reset) triggers one full reload,
  never a partial guess;
* the cache never invents a sample. If the backend has nothing new, the context simply does not move.
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any

from .backend import BackendClient, BackendError

log = logging.getLogger("aqss.ai.cache")

WINDOW_MINUTES = 90


@dataclass
class DeviceCache:
    device_id: str
    samples: list[dict[str, Any]] = field(default_factory=list)
    actuator: list[dict[str, Any]] = field(default_factory=list)
    after_id: int = 0
    actuator_after_id: int = 0
    loaded_at: datetime | None = None
    reloads: int = 0
    last_error: str | None = None

    @property
    def ready(self) -> bool:
        return self.loaded_at is not None


class ContextCache:
    """One cache per device, refreshed with bounded incremental reads."""

    def __init__(self, client: BackendClient, window_minutes: int = WINDOW_MINUTES):
        self.client = client
        self.window = timedelta(minutes=window_minutes)
        self._devices: dict[str, DeviceCache] = {}
        self._lock = threading.Lock()

    def full_reload(self, device_id: str) -> DeviceCache:
        """Load the whole window once, then remember the high-water ids."""
        with self._lock:
            cache = self._devices.setdefault(device_id, DeviceCache(device_id))
        rows = self.client.recent(device_id, int(self.window.total_seconds() // 60))
        actuator = self.client.recent_actuator(device_id, int(self.window.total_seconds() // 60))
        cache.samples = rows
        cache.actuator = actuator
        cache.after_id = max((int(row["id"]) for row in rows if row.get("id") is not None), default=0)
        cache.actuator_after_id = max(
            (int(row["id"]) for row in actuator if row.get("id") is not None), default=0)
        cache.loaded_at = datetime.now(timezone.utc)
        cache.reloads += 1
        cache.last_error = None
        self._trim(cache)
        log.info("cache loaded for %s: %d samples, %d actuator rows (after_id=%d)",
                 device_id, len(cache.samples), len(cache.actuator), cache.after_id)
        return cache

    def refresh(self, device_id: str) -> DeviceCache:
        """
        Full load on first use; afterwards one incremental page per stream.

        A `BackendError` is recorded and re-raised so the caller can report "stale" instead of
        silently forecasting from an old window.
        """
        cache = self._devices.get(device_id)
        if cache is None or not cache.ready:
            return self.full_reload(device_id)
        try:
            page = self.client.samples_after(device_id, cache.after_id)
            if page.get("items"):
                self._merge(cache.samples, page["items"])
                cache.after_id = int(page.get("next_after_id") or cache.after_id)
            actuator_page = self.client.actuator_reports_after(device_id, cache.actuator_after_id)
            if actuator_page.get("items"):
                self._merge(cache.actuator, actuator_page["items"])
                cache.actuator_after_id = int(
                    actuator_page.get("next_after_id") or cache.actuator_after_id)
            self._trim(cache)
            cache.last_error = None
            return cache
        except BackendError as error:
            cache.last_error = str(error)[:200]
            log.warning("incremental read failed for %s: %s", device_id, error)
            # The id space restarted or the gap is too wide: one full reload is cheaper than a wrong
            # context.
            if "device not found" in str(error).lower():
                raise
            return self.full_reload(device_id)

    def rows(self, device_id: str, now: datetime | None = None) -> tuple[list[dict], list[dict]]:
        cache = self.refresh(device_id)
        issued = now or datetime.now(timezone.utc)
        floor = issued - self.window
        samples = [row for row in cache.samples
                   if (at := _instant(row.get("recorded_at"))) is not None and at >= floor]
        actuator = [row for row in cache.actuator
                    if (at := _instant(row.get("received_at") or row.get("recorded_at"))) is not None
                    and at >= floor]
        return samples, actuator

    def rows_with_fan(self, device_id: str,
                      now: datetime | None = None) -> tuple[list[dict], list[dict]]:
        """
        Samples with the fan state that was *known* when each sample was taken.

        Telemetry carries no fan level: the node reports it on a separate topic. Without this join
        the model sees a NaN fan column and silently loses the strongest covariate in the data. The
        join is as-of on the actuator **receive** clock, so a report that arrived after a sample
        cannot describe that sample.
        """
        samples, actuator = self.rows(device_id, now)
        if not samples or not actuator:
            return samples, actuator

        import pandas as pd

        reports = []
        for row in actuator:
            level = row.get("level")
            if level is None:
                continue
            reports.append(
                {
                    "received_at": pd.Timestamp(_instant(row.get("received_at") or row.get("recorded_at"))),
                    "fan_level": float(level),
                }
            )
        if not reports:
            return samples, actuator

        left = pd.DataFrame(
            {"recorded_at": [pd.Timestamp(_instant(row.get("recorded_at"))) for row in samples]}
        )
        right = pd.DataFrame(reports).sort_values("received_at")
        merged = pd.merge_asof(
            left.sort_values("recorded_at"),
            right,
            left_on="recorded_at",
            right_on="received_at",
            direction="backward",
            tolerance=pd.Timedelta(minutes=30),
        )
        enriched: list[dict] = []
        for sample, level in zip(samples, merged["fan_level"].tolist()):
            # A sample taken before the first report has no known fan state; NaN, not a guess.
            enriched.append({**sample, "fan_level": None if pd.isna(level) else level})
        return enriched, actuator

    def stats(self, device_id: str) -> dict[str, Any]:
        cache = self._devices.get(device_id)
        if cache is None:
            return {"status": "cold"}
        return {
            "samples": len(cache.samples),
            "actuator": len(cache.actuator),
            "after_id": cache.after_id,
            "reloads": cache.reloads,
            "loaded_at": cache.loaded_at.isoformat() if cache.loaded_at else None,
            "last_error": cache.last_error,
        }

    def _trim(self, cache: DeviceCache) -> None:
        """Keep the window bounded; the backend is the source, not this cache."""
        floor = datetime.now(timezone.utc) - self.window - timedelta(minutes=5)
        cache.samples = [row for row in cache.samples
                         if (at := _instant(row.get("recorded_at"))) is None or at >= floor]
        cache.actuator = [row for row in cache.actuator
                          if (at := _instant(row.get("received_at") or row.get("recorded_at"))) is None
                          or at >= floor]

    @staticmethod
    def _merge(target: list[dict[str, Any]], incoming: list[dict[str, Any]]) -> None:
        """
        Add rows the cache does not already hold, keyed by id.

        A cursor can move backwards after a failure or a retry, and the backend honestly returns the
        rows again. Appending blindly would duplicate every sample in that overlap, so the context
        would see the same second twice.
        """
        seen = {row.get("id") for row in target if row.get("id") is not None}
        for row in incoming:
            row_id = row.get("id")
            if row_id is None or row_id not in seen:
                target.append(row)
                if row_id is not None:
                    seen.add(row_id)


def _instant(value: Any) -> datetime | None:
    if value is None:
        return None
    from .data import parse_instant

    return parse_instant(value)
