import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd


def generate(count: int, seed: int) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    volumes = rng.uniform(10, 500, count)
    air_exchange = rng.uniform(0.2, 1.5, count)
    ventilation_code = rng.integers(0, 3, count)
    ventilation = np.choose(ventilation_code, ["low", "medium", "high"])
    penetration = rng.uniform(0.5, 0.9, count)
    cadr = rng.uniform(30, 400, count)
    outdoor = rng.uniform(5, 200, count)
    occupancy = rng.integers(1, 51, count)
    generation = rng.uniform(0, 2000, count)
    purpose = rng.choice(["bedroom", "classroom", "office", "living_room", "dorm"], count)
    region = rng.choice(["north", "central", "south"], count)
    exposure_hours = np.select(
        [np.isin(purpose, ["bedroom", "dorm"]), purpose == "classroom", purpose == "office"],
        [8, 6, 8], default=4,
    )
    steady_state = (penetration * outdoor * air_exchange * volumes + generation) / (air_exchange * volumes + cadr)
    target_daily_average = np.full(count, 15.0)
    threshold = np.clip(target_daily_average * (24 / exposure_hours) + 0.1 * steady_state, 5, 100)

    current_pm25 = threshold + rng.uniform(1, 150, count)
    candidate_levels = np.array([1, 2, 3])
    level_cadr = cadr[:, None] * np.array([0.35, 0.60, 0.90])[None, :]
    decay_rate = air_exchange[:, None] + level_cadr / volumes[:, None]
    equilibrium = (penetration * outdoor * air_exchange * volumes + generation)[:, None] / (air_exchange[:, None] * volumes[:, None] + level_cadr)
    feasible = equilibrium < threshold[:, None]
    clean_hours = np.where(
        feasible,
        np.log(np.maximum(current_pm25[:, None] - equilibrium, 1e-6)
               / np.maximum(threshold[:, None] - equilibrium, 1e-6)) / decay_rate,
        np.inf,
    )
    clean_minutes = np.maximum(np.ceil(clean_hours * 60), 1)
    acceptable = clean_minutes <= 120
    chosen = np.argmax(acceptable, axis=1)
    has_solution = acceptable.any(axis=1)
    level = np.where(has_solution, candidate_levels[chosen], 3)
    duration = np.where(has_solution, clean_minutes[np.arange(count), chosen], 120).astype(int)

    return pd.DataFrame({
        "volume_m3": volumes,
        "purpose": purpose,
        "purpose_code": pd.Series(purpose).map({"bedroom": 0, "classroom": 1, "office": 2, "living_room": 3, "dorm": 4}).to_numpy(),
        "region": region,
        "region_code": pd.Series(region).map({"north": 0, "central": 1, "south": 2}).to_numpy(),
        "ventilation_estimate": ventilation,
        "ventilation_code": ventilation_code,
        "penetration_factor": penetration,
        "natural_air_exchange_per_hour": air_exchange,
        "indoor_generation_ug_h": generation,
        "exposure_duration_hours": exposure_hours,
        "occupancy_typical": occupancy,
        "outdoor_pm25_estimate": outdoor,
        "filter_cadr": cadr,
        "current_pm25": current_pm25,
        "base_threshold": threshold,
        "threshold_label_pm25": threshold,
        "dosage_level_label": level,
        "dosage_duration_minutes_label": duration,
    })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--seed", type=int, default=20261016)
    parser.add_argument("--output", type=Path, default=Path("data/processed/synthetic_ml_labels.csv"))
    args = parser.parse_args()
    if args.count < 1:
        parser.error("--count must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    generate(args.count, args.seed).to_csv(args.output, index=False)
    manifest = {
        "dataset": args.output.name,
        "rows": args.count,
        "seed": args.seed,
        "label_source": "synthetic physics-informed surrogate; not measured ground truth",
        "standards_assumption": "WHO 2021 24h guideline and simplified occupancy factor; validation required",
    }
    args.output.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
