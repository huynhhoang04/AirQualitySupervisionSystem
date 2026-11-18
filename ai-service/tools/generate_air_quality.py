"""Generate synthetic room sessions for Chronos-2 fine-tuning.

Protocol: docs/upgrade-plan/08-ai-data-analysis-and-training.md §3-§5.

Design rules this file enforces:

* one row per simulated 5 s step, written as Parquet shards, so 34.56 M rows never need to fit
  in memory together with a model;
* the mass balance is a scenario generator, not a measured fan CADR;
* CO2 has its own source and ventilation term; HEPA never removes CO2;
* faults are recorded explicitly (`dust_fault`, `pm25_saturated`, `missing_reason`) and masked,
  never clipped into a plausible reading;
* every session carries `source`, `episode_id`, `room_id`, `seed` and its scenario, and each
  split is made of whole sessions;
* latent truth (unnoised PM, future dust source) lives in a separate debug file, never in the
  training schema.

Usage:
    .venv-chronos/bin/python tools/generate_air_quality.py --config configs/air_quality_synthetic.yaml
    .venv-chronos/bin/python tools/generate_air_quality.py --config ... --sessions 4 --output out/smoke
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import yaml

SOURCE = "synthetic"

SCHEMA = pa.schema(
    [
        ("source", pa.string()),
        ("episode_id", pa.string()),
        ("room_id", pa.string()),
        ("seed", pa.int64()),
        ("split", pa.string()),
        ("timestamp", pa.timestamp("ms", tz="UTC")),
        ("received_at", pa.timestamp("ms", tz="UTC")),
        ("pm25", pa.float32()),
        ("co2", pa.float32()),
        ("temperature", pa.float32()),
        ("humidity", pa.float32()),
        ("fan_level", pa.int8()),
        ("fan_mode", pa.string()),
        ("fan_on", pa.bool_()),
        ("dust_fault", pa.bool_()),
        ("pm25_saturated", pa.bool_()),
        ("missing_reason", pa.string()),
        ("scenario", pa.string()),
        ("volume_m3", pa.float32()),
        ("ventilation_rate", pa.float32()),
        ("people", pa.int8()),
    ]
)


def _range(config: dict[str, Any], key: str) -> tuple[float, float]:
    low, high = config[key]
    return float(low), float(high)


@dataclass
class Session:
    episode_id: str
    split: str
    scenario: str
    index: int
    seed: int
    hours: float
    step_seconds: float
    volume_m3: float
    ventilation_per_hour: float
    outdoor_pm25: float
    filter_response: dict[int, float]
    settings: dict[str, Any]


def _pick(rng: np.random.Generator, config: dict[str, Any], key: str) -> float:
    low, high = _range(config, key)
    return float(rng.uniform(low, high))


def _scenario(rng: np.random.Generator, split: str, index: int, variation: bool) -> str:
    base = [
        "background",
        "dust_ramp",
        "dust_pulse",
        "fan_policy",
        "ventilation_shift",
        "occupied_room",
        "sensor_faults",
        "mixed",
    ]
    if split != "test":
        return base[index % len(base)]
    # Test scenarios intentionally include parameter ranges the training split never used.
    return ["background", "dust_ramp", "dust_pulse", "mixed", "unseen_spike", "long_gap"][
        0 if not variation else index % 6
    ]


def build_sessions(config: dict[str, Any], split: str, count: int) -> list[Session]:
    rng = np.random.default_rng(int(config["seed"]) + {"train": 0, "validation": 1, "test": 2}[split])
    settings = config
    sessions = []
    for index in range(count):
        scenario = _scenario(rng, split, index, bool(config["split"].get("test_variation", False)))
        filter_response = {
            level: _pick(rng, config["filter"], f"level_{name}")
            for level, name in ((0, "off"), (1, "1"), (2, "2"), (3, "3"))
        }
        sessions.append(
            Session(
                episode_id=f"{split}-{index:05d}",
                split=split,
                scenario=scenario,
                index=index,
                seed=int(rng.integers(0, 2**31 - 1)),
                hours=float(config["sessions"]["hours"]),
                step_seconds=float(config["sessions"]["step_seconds"]),
                volume_m3=_pick(rng, config["room"], "volume_m3"),
                ventilation_per_hour=_pick(rng, config["room"], "ventilation_per_hour"),
                outdoor_pm25=_pick(rng, config["room"], "outdoor_pm25"),
                filter_response=filter_response,
                settings=settings,
            )
        )
    return sessions


class FanPolicy:
    """Three policies so training does not learn one controller by heart."""

    def __init__(self, session: Session, rng: np.random.Generator):
        cfg = session.settings["fan"]
        self.rng = rng
        self.kind = str(cfg["policies"][session.index % len(cfg["policies"])])
        self.threshold = _pick(rng, cfg, "threshold_ug_m3")
        self.off_threshold = self.threshold * _pick(rng, cfg, "off_ratio")
        self.min_on = _pick(rng, cfg, "min_on_minutes")
        self.max_on = _pick(rng, cfg, "max_on_minutes")
        self.schedule_on = rng.integers(6, 12)
        self.schedule_off = rng.integers(16, 23)
        self.level = 0
        self.on_since: float | None = None
        self.manual_level = int(rng.integers(0, 4))

    def step(self, hour: float, pm: float) -> int:
        two_hours = hour % 2.0
        if self.kind == "scheduled":
            desired = 2 if self.schedule_on <= hour < self.schedule_off else 0
            return desired
        if self.kind == "manual":
            # A hand on the control: switch every two hours, independent of the reading.
            if two_hours < self.rng.uniform(0.0, 0.02):
                self.manual_level = int(self.rng.integers(0, 4))
            return self.manual_level

        on = self.level > 0
        running = 0.0 if self.on_since is None else (hour - self.on_since) * 60.0
        if not on and pm >= self.threshold:
            self.on_since = hour
            self.level = 3
        elif on and running >= self.max_on:
            self.level = 0
            self.on_since = None
        elif on and running >= self.min_on and pm < self.off_threshold:
            self.level = 0
            self.on_since = None
        elif on and running >= self.min_on and pm < self.off_threshold * 0.9:
            self.level = 2
        return self.level


def simulate(session: Session) -> tuple[pd.DataFrame, pd.DataFrame]:
    rng = np.random.default_rng(session.seed)
    cfg = session.settings
    steps = int(session.hours * 3600 / session.step_seconds)
    dt_hours = session.step_seconds / 3600.0
    start = pd.Timestamp("2026-01-05T00:00:00Z") + pd.Timedelta(days=session.index % 60)
    times = pd.date_range(start, periods=steps, freq=f"{int(session.step_seconds)}s", tz="UTC")

    # Dust source: background plus Poisson events whose shape depends on the scenario.
    source_event_rate = _pick(rng, cfg["sources"], "event_rate_per_hour")
    if session.scenario in ("dust_ramp", "unseen_spike"):
        source_event_rate *= 2.5
    if session.scenario == "background":
        source_event_rate *= 0.1
    events = rng.poisson(source_event_rate * session.hours)
    source = np.full(steps, _pick(rng, cfg["sources"], "background"), dtype=np.float64)
    for _ in range(int(events)):
        at = int(rng.integers(0, steps))
        length = int(_pick(rng, cfg["sources"], "event_duration_minutes") * 60 / session.step_seconds)
        intensity = _pick(rng, cfg["sources"], "event_intensity")
        shape = rng.choice(["pulse", "ramp", "decay"], p=[0.4, 0.3, 0.3])
        window = np.arange(min(length, steps - at), dtype=np.float64)
        if len(window) == 0:
            continue
        if shape == "pulse":
            profile = np.exp(-((window - len(window) / 3) ** 2) / max(1.0, (len(window) / 4) ** 2))
        elif shape == "ramp":
            profile = window / max(1.0, len(window) - 1)
        else:
            profile = np.exp(-window / max(1.0, len(window) / 3))
        # The source is already ug/(m3*h); the balance step multiplies by dt_hours.
        source[at : at + len(window)] += intensity * profile

    # Occupancy window and ventilation; CO2 is generated by people and removed by ventilation.
    occ_start = _pick(rng, cfg["occupancy"], "start_hour")
    occ_end = _pick(rng, cfg["occupancy"], "end_hour")
    people = int(rng.integers(cfg["occupancy"]["people"][0], cfg["occupancy"]["people"][1] + 1))
    if session.scenario == "occupied_room":
        people = max(people, 4)
    hour_of_day = (times.hour + times.minute / 60.0).to_numpy()
    occupied = ((hour_of_day >= occ_start) & (hour_of_day < occ_end)).astype(np.float64)

    ventilation = session.ventilation_per_hour
    if session.scenario == "ventilation_shift":
        ventilation *= 0.35

    co2_production = _pick(rng, cfg["occupancy"], "co2_generation_ppm_per_hour_per_person") * people

    policy = FanPolicy(session, rng)
    pm = np.zeros(steps, dtype=np.float64)
    co2 = np.full(steps, 420.0, dtype=np.float64)
    temperature = np.full(steps, 24.0, dtype=np.float64)
    humidity = np.full(steps, 55.0, dtype=np.float64)
    fan_levels = np.zeros(steps, dtype=np.int8)
    latent = np.zeros(steps, dtype=np.float64)

    pm[0] = session.outdoor_pm25 * 0.5
    co2[0] = 420.0
    for i in range(steps):
        hour = hour_of_day[i]
        if i > 0:
            level_prev = int(fan_levels[i - 1])
            removal = session.filter_response[level_prev] * dt_hours
            exchange = ventilation * dt_hours
            gain = source[i] * dt_hours
            dilution = exchange * max(0.0, session.outdoor_pm25 - pm[i - 1])
            pm[i] = max(0.0, pm[i - 1] + gain + dilution - removal * pm[i - 1])
            occupied_now = occupied[i]
            co2[i] = max(
                400.0,
                co2[i - 1]
                + (co2_production if occupied_now else 0.0) * dt_hours
                - ventilation * (co2[i - 1] - 420.0) * dt_hours,
            )
            temperature[i] = 24.0 + 1.5 * math.sin(2 * math.pi * hour / 24.0) * 0.2
            humidity[i] = 55.0 + 4.0 * math.sin(2 * math.pi * hour / 12.0) * 0.3
        latent[i] = pm[i]
        level = policy.step(hour, pm[i])
        fan_levels[i] = level

    # Sensor layer: noise, saturation, faults and gaps.
    pm_meas = pm + rng.normal(0.0, _pick(rng, cfg["sensors"], "pm_noise_ug_m3"), steps)
    pm_meas = np.clip(pm_meas, 0.0, None)
    saturation = float(cfg["sensors"]["saturation_ug_m3"])
    saturated = pm_meas >= saturation
    pm_meas = np.minimum(pm_meas, saturation)

    co2_meas = co2 + rng.normal(0.0, _pick(rng, cfg["sensors"], "co2_noise_ppm"), steps)
    temp_meas = temperature + rng.normal(0.0, _pick(rng, cfg["sensors"], "temperature_noise_c"), steps)
    hum_meas = humidity + rng.normal(0.0, _pick(rng, cfg["sensors"], "humidity_noise_pct"), steps)
    hum_meas = np.clip(hum_meas, 0.0, 100.0)

    dust_fault = np.zeros(steps, dtype=bool)
    fault_rate = float(cfg["faults"]["dust_fault_rate"][1])
    if session.scenario in ("sensor_faults", "long_gap"):
        fault_rate = max(fault_rate, 0.02)
    fault_count = rng.poisson(fault_rate * session.hours)
    for _ in range(int(fault_count)):
        at = int(rng.integers(0, steps))
        length = int(rng.integers(1, max(2, int(120 / session.step_seconds))))
        dust_fault[at : at + length] = True

    missing_reason = np.full(steps, None, dtype=object)
    gap_rate = _pick(rng, cfg["faults"], "gap_rate_per_hour")
    if session.scenario in ("long_gap", "sensor_faults"):
        gap_rate = max(gap_rate, 0.5)
    gap_count = rng.poisson(gap_rate * session.hours)
    for _ in range(int(gap_count)):
        at = int(rng.integers(0, steps))
        length = int(_pick(rng, cfg["faults"], "gap_minutes") * 60 / session.step_seconds)
        missing_reason[at : at + length] = "network_gap"

    skew = rng.uniform(*cfg["faults"]["clock_skew_seconds"])
    received = times + pd.to_timedelta(rng.normal(0.4, 0.25, steps), unit="s")
    if session.scenario == "sensor_faults":
        received = received + pd.to_timedelta(skew, unit="s")
    # The Parquet schema is millisecond resolution; keep sub-millisecond noise out of the file.
    received = received.floor("ms")

    frame = pd.DataFrame(
        {
            "source": SOURCE,
            "episode_id": session.episode_id,
            "room_id": f"room-{session.index:04d}",
            "seed": session.seed,
            "split": session.split,
            "timestamp": times,
            "received_at": received,
            "pm25": pm_meas.astype(np.float32),
            "co2": co2_meas.astype(np.float32),
            "temperature": temp_meas.astype(np.float32),
            "humidity": hum_meas.astype(np.float32),
            "fan_level": fan_levels,
            "fan_mode": "AUTO",
            "fan_on": fan_levels > 0,
            "dust_fault": dust_fault,
            "pm25_saturated": saturated,
            "missing_reason": missing_reason,
            "scenario": session.scenario,
            "volume_m3": np.float32(session.volume_m3),
            "ventilation_rate": np.float32(session.ventilation_per_hour),
            "people": np.int8(people),
        }
    )
    # A fault or gap means "no reading", not a bad value: the measurement is dropped.
    dropped = frame["dust_fault"] | frame["missing_reason"].notna()
    for column in ("pm25", "co2", "temperature", "humidity"):
        frame.loc[dropped, column] = np.nan

    debug = pd.DataFrame(
        {
            "episode_id": session.episode_id,
            "timestamp": times,
            "latent_pm25": latent.astype(np.float32),
            "source_strength": source.astype(np.float32),
            "occupied": occupied.astype(bool),
        }
    )
    return frame, debug


def write_split(sessions: list[Session], output: Path, shard_hours: int = 24) -> dict[str, Any]:
    output.mkdir(parents=True, exist_ok=True)
    manifest: dict[str, Any] = {"source": SOURCE, "sessions": len(sessions), "shards": [], "rows": 0}
    debug_frames: list[pd.DataFrame] = []
    for session in sessions:
        frame, debug = simulate(session)
        debug_frames.append(debug)
        steps_per_shard = int(shard_hours * 3600 / session.step_seconds)
        for offset in range(0, len(frame), steps_per_shard):
            part = frame.iloc[offset : offset + steps_per_shard]
            # `output` is already the split directory; keep the shard name flat inside it.
            name = f"{session.split}/{session.episode_id}-{offset // steps_per_shard:02d}.parquet"
            path = output / f"{session.episode_id}-{offset // steps_per_shard:02d}.parquet"
            path.parent.mkdir(parents=True, exist_ok=True)
            table = pa.Table.from_pandas(part, schema=SCHEMA, preserve_index=False)
            pq.write_table(table, path, compression="zstd")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            manifest["shards"].append(
                {"path": name, "rows": len(part), "sha256": digest,
                 "episode_id": session.episode_id, "scenario": session.scenario}
            )
            manifest["rows"] += len(part)
    debug_dir = output / "_debug"
    debug_dir.mkdir(parents=True, exist_ok=True)
    pd.concat(debug_frames).to_parquet(debug_dir / "latent.parquet", index=False)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="configs/air_quality_synthetic.yaml")
    parser.add_argument("--output", default="data/synthetic")
    parser.add_argument("--sessions", type=int, default=None,
                        help="Cap per split for a smoke test; omit for the configured size.")
    parser.add_argument("--splits", default="train,validation,test")
    args = parser.parse_args()

    config = yaml.safe_load(Path(args.config).read_text(encoding="utf-8"))
    splits = [split.strip() for split in args.splits.split(",") if split.strip()]
    totals = {}
    for split in splits:
        count = int(config["split"][split])
        if args.sessions is not None:
            count = min(count, args.sessions)
        sessions = build_sessions(config, split, count)
        print(f"{split}: {len(sessions)} sessions ...", flush=True)
        manifest = write_split(sessions, Path(args.output) / split)
        totals[split] = {"sessions": len(sessions), "rows": manifest["rows"],
                         "shards": len(manifest["shards"])}
        print(f"{split}: {manifest['rows']:,} rows in {len(manifest['shards'])} shards", flush=True)

    summary = {"config": str(args.config), "output": str(args.output), "splits": totals,
               "seed": config["seed"], "step_seconds": config["sessions"]["step_seconds"]}
    (Path(args.output) / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
