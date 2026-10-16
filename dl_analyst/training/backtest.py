import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from app.forecast_models import Chronos2Adapter, PersistenceBaseline, SeasonalNaiveBaseline


def pinball_loss(actual: np.ndarray, predicted: np.ndarray, tau: float) -> float:
    error = actual - predicted
    return float(np.mean(np.maximum(tau * error, (tau - 1) * error)))


def score(actual: np.ndarray, quantiles: np.ndarray) -> dict:
    median = quantiles[:, 1]
    return {
        "mae": float(np.mean(np.abs(actual - median))),
        "rmse": float(np.sqrt(np.mean((actual - median) ** 2))),
        "pinball_q10": pinball_loss(actual, quantiles[:, 0], 0.1),
        "pinball_q50": pinball_loss(actual, quantiles[:, 1], 0.5),
        "pinball_q90": pinball_loss(actual, quantiles[:, 2], 0.9),
        "interval_80_coverage": float(np.mean((actual >= quantiles[:, 0]) & (actual <= quantiles[:, 2]))),
        "interval_80_width": float(np.mean(quantiles[:, 2] - quantiles[:, 0])),
    }


def evaluate_series(values: np.ndarray, context_steps: int, horizon: int, origins: int, models: dict) -> dict:
    results = {name: [] for name in models}
    valid = np.isfinite(values)
    boundaries = np.diff(np.r_[False, valid, False].astype(np.int8))
    runs = list(zip(np.flatnonzero(boundaries == 1), np.flatnonzero(boundaries == -1)))
    windows = []
    for start, end in runs:
        if end - start < context_steps + horizon:
            continue
        first_origin = max(start + context_steps, end - origins * horizon)
        windows.extend((origin, start, end) for origin in range(first_origin, end - horizon + 1, horizon))
    for origin, start, end in windows:
        context = values[origin - context_steps:origin]
        actual = values[origin:origin + horizon]
        if origin - context_steps < start or origin + horizon > end:
            continue
        for name, model in models.items():
            prediction = model.predict(context, horizon).quantiles
            results[name].append(score(actual, prediction))
    return {
        "evaluated_origins": len(next(iter(results.values()))) if results else 0,
        "scores": {
            name: {
                metric: float(np.mean([row[metric] for row in rows]))
                for metric in (rows[0].keys() if rows else [])
            }
            for name, rows in results.items()
        },
    }


def load_hourly_series(path: Path, source_id: str, site_id: str) -> np.ndarray:
    files = [path] if path.is_file() else sorted(path.rglob("*.parquet"))
    if not files:
        raise FileNotFoundError(f"no Parquet files found under {path}")
    frame = pd.concat((pd.read_parquet(file) for file in files), ignore_index=True)
    selected = frame[(frame["source_id"] == source_id) & (frame["site_id"] == site_id)]
    selected = selected.sort_values("timestamp_utc")
    if selected.empty:
        raise ValueError(f"no rows for source_id={source_id}, site_id={site_id} under {path}")
    series = selected.set_index("timestamp_utc")["pm25_ug_m3"]
    full_index = pd.date_range(series.index.min(), series.index.max(), freq="h", tz="UTC")
    series = series.reindex(full_index)
    return series.to_numpy(dtype=np.float32)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--parquet", type=Path, required=True)
    parser.add_argument("--source-id", required=True)
    parser.add_argument("--site-id", required=True)
    parser.add_argument("--context", type=int, default=96)
    parser.add_argument("--horizon", type=int, default=24)
    parser.add_argument("--origins", type=int, default=20)
    parser.add_argument("--chronos", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("data/reports/backtest.json"))
    args = parser.parse_args()
    values = load_hourly_series(args.parquet, args.source_id, args.site_id)
    models = {
        "persistence": PersistenceBaseline(),
        "seasonal_naive": SeasonalNaiveBaseline(season_length=24),
    }
    if args.chronos:
        models["chronos_2"] = Chronos2Adapter()
    report = {
        "source_id": args.source_id,
        "site_id": args.site_id,
        "cadence": "1h",
        "context_steps": args.context,
        "horizon_steps": args.horizon,
        **evaluate_series(values, args.context, args.horizon, args.origins, models),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
