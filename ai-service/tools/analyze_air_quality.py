"""EDA for real and synthetic sessions, producing the report figures (plan 08 §6).

Answers the questions the defence will ask, and states what the chart does *not* prove:

* coverage over time — how much usable data exists, and where the gaps are;
* sensor distributions, including saturation and out-of-range;
* packet interval distribution, so "SSE is fast" never becomes "the sensor measures faster";
* autocorrelation of PM at several lags;
* correlation and cross-correlation between sensors (correlation is not causation);
* PM around fan-level changes, which is a feedback loop, not evidence the fan creates dust;
* real-vs-synthetic distribution comparison.

Usage:
    .venv-chronos/bin/python tools/analyze_air_quality.py --synthetic data/synthetic \\
        --output reports/eda
"""

from __future__ import annotations

import argparse
import glob
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import pyarrow.parquet as pq


def load_synthetic(root: Path) -> pd.DataFrame:
    files = [f for f in sorted(glob.glob(str(root / "*" / "*.parquet"))) if "_debug" not in f]
    if not files:
        return pd.DataFrame()
    frames = [pq.read_table(file).to_pandas() for file in files]
    return pd.concat(frames, ignore_index=True)


def coverage_table(frame: pd.DataFrame) -> pd.DataFrame:
    return (
        frame.groupby(["split", "episode_id"])
        .agg(
            rows=("pm25", "size"),
            pm_valid=("pm25", lambda s: float(s.notna().mean())),
            co2_valid=("co2", lambda s: float(s.notna().mean())),
            saturated=("pm25_saturated", "mean"),
            dust_fault=("dust_fault", "mean"),
            fan_on=("fan_on", "mean"),
        )
        .reset_index()
    )


def packet_intervals(frame: pd.DataFrame) -> pd.Series:
    gaps = []
    for _, group in frame.groupby("episode_id"):
        ordered = group["timestamp"].sort_values()
        gaps.append(ordered.diff().dt.total_seconds().dropna())
    return pd.concat(gaps) if gaps else pd.Series(dtype=float)


def autocorrelation(values: pd.Series, lags: list[int]) -> dict[int, float]:
    clean = values.dropna().to_numpy(dtype=float)
    if len(clean) < max(lags) + 2 or np.std(clean) == 0:
        return {}
    centered = clean - clean.mean()
    denominator = float(np.dot(centered, centered))
    return {lag: round(float(np.dot(centered[:-lag], centered[lag:]) / denominator), 4)
            for lag in lags if lag < len(centered)}


def cross_correlation(pm: pd.Series, other: pd.Series, max_lag: int = 60) -> list[tuple[int, float]]:
    pair = pd.concat([pm, other], axis=1).dropna()
    if len(pair) < 200:
        return []
    a = pair.iloc[:, 0].to_numpy(dtype=float)
    b = pair.iloc[:, 1].to_numpy(dtype=float)
    if np.std(a) == 0 or np.std(b) == 0:
        return []
    a = (a - a.mean()) / a.std()
    b = (b - b.mean()) / b.std()
    result = []
    for lag in range(-max_lag, max_lag + 1, 5):
        if lag < 0:
            value = float(np.mean(a[-lag:] * b[: len(b) + lag]))
        elif lag > 0:
            value = float(np.mean(a[: len(a) - lag] * b[lag:]))
        else:
            value = float(np.mean(a * b))
        result.append((lag, round(value, 4)))
    return result


def fan_episodes(frame: pd.DataFrame) -> pd.DataFrame:
    """
    Measure the PM *slope* before and after a fan-level change.

    Comparing raw mean before/after is misleading: the source event that triggered the fan is
    often still active afterwards, so PM can be high on both sides. The slope separates "the room
    is still filling" from "the filter is winning", which is the question this figure answers.
    """
    records = []
    for episode, group in frame.groupby("episode_id"):
        ordered = group.sort_values("timestamp")
        change = ordered["fan_level"].diff().fillna(0) != 0
        for position in np.flatnonzero(change.to_numpy()):
            if position < 60 or position + 120 >= len(ordered):
                continue
            before = ordered["pm25"].iloc[position - 60 : position].dropna()
            after = ordered["pm25"].iloc[position : position + 120].dropna()
            if len(before) < 30 or len(after) < 60:
                continue
            records.append(
                {
                    "episode_id": episode,
                    "at": ordered["timestamp"].iloc[position],
                    "from_level": int(ordered["fan_level"].iloc[position - 1]),
                    "to_level": int(ordered["fan_level"].iloc[position]),
                    "pm_before_5min": round(float(before.mean()), 2),
                    "pm_after_10min": round(float(after.mean()), 2),
                    # ug/m3 per minute over each window.
                    "slope_before": round(float(before.iloc[-1] - before.iloc[0]) / 5.0, 3),
                    "slope_after": round(float(after.iloc[-1] - after.iloc[0]) / 10.0, 3),
                }
            )
    return pd.DataFrame(records)


def safe_correlation(frame: pd.DataFrame, column: str) -> dict[str, object] | None:
    """Pearson/Spearman on every available pair; a constant series has no defined correlation."""
    if column not in frame:
        return None
    pair = frame[["pm25", column]].dropna()
    if len(pair) < 30 or pair["pm25"].nunique() < 2 or pair[column].nunique() < 2:
        return None
    return {
        "pearson": round(float(pair.corr(method="pearson").iloc[0, 1]), 3),
        "spearman": round(float(pair.corr(method="spearman").iloc[0, 1]), 3),
        "pairs": int(len(pair)),
    }


def plot_coverage(frame: pd.DataFrame, path: Path) -> None:
    figure, axes = plt.subplots(1, 1, figsize=(11, 3.2))
    valid = frame.dropna(subset=["pm25"])
    valid = valid.assign(hour=valid["timestamp"].dt.floor("h"))
    per_hour = valid.groupby("hour").size()
    axes.plot(per_hour.index, per_hour.values, linewidth=1.0, color="#0891b2")
    axes.set_title("PM2.5 samples per clock hour (all sessions)")
    axes.set_ylabel("valid samples")
    axes.grid(alpha=0.25)
    figure.tight_layout()
    figure.savefig(path, dpi=140)
    plt.close(figure)


def plot_distributions(frame: pd.DataFrame, path: Path) -> None:
    figure, axes = plt.subplots(1, 4, figsize=(14, 3))
    for axis, column, color in zip(
        axes, ["pm25", "co2", "temperature", "humidity"], ["#0891b2", "#7c3aed", "#f59e0b", "#10b981"]
    ):
        values = frame[column].dropna()
        axis.hist(values, bins=60, color=color, alpha=0.85)
        axis.set_title(column)
        axis.grid(alpha=0.2)
    figure.suptitle("Sensor distributions (valid measurements only)")
    figure.tight_layout()
    figure.savefig(path, dpi=140)
    plt.close(figure)


def plot_acf(values: pd.Series, path: Path) -> None:
    lags = [1, 2, 3, 6, 12, 30, 60, 120, 300, 720]
    computed = autocorrelation(values, lags)
    figure, axis = plt.subplots(figsize=(7, 3))
    axis.bar([f"{lag * 5}s" for lag in computed], list(computed.values()), color="#0891b2")
    axis.set_ylim(0, 1)
    axis.set_ylabel("autocorrelation")
    axis.set_title("PM2.5 autocorrelation by lag (5 s steps)")
    axis.grid(alpha=0.25, axis="y")
    figure.tight_layout()
    figure.savefig(path, dpi=140)
    plt.close(figure)
    return computed


def plot_cross_correlation(pairs: dict[str, list[tuple[int, float]]], path: Path) -> None:
    figure, axis = plt.subplots(figsize=(7, 3))
    for name, series in pairs.items():
        if not series:
            continue
        axis.plot([lag for lag, _ in series], [value for _, value in series], label=name)
    axis.axhline(0, color="#64748b", linewidth=0.8)
    axis.set_xlabel("lag in 5 s steps (positive = sensor leads PM)")
    axis.set_ylabel("correlation")
    axis.set_title("Cross-correlation with PM2.5")
    axis.legend(fontsize=8)
    axis.grid(alpha=0.25)
    figure.tight_layout()
    figure.savefig(path, dpi=140)
    plt.close(figure)


def plot_fan_episodes(episodes: pd.DataFrame, path: Path) -> None:
    usable = episodes.dropna(subset=["slope_before", "slope_after"]) if not episodes.empty else episodes
    figure, axis = plt.subplots(figsize=(7, 3.6))
    if not usable.empty:
        for label, group in usable.groupby(usable["to_level"] > usable["from_level"]):
            axis.scatter(
                group["slope_before"],
                group["slope_after"],
                s=16,
                alpha=0.65,
                label="fan turned up" if label else "fan turned down",
            )
        low = float(min(usable["slope_before"].min(), usable["slope_after"].min())) * 1.15
        high = float(max(usable["slope_before"].max(), usable["slope_after"].max())) * 1.15
        axis.plot([low, high], [low, high], color="#94a3b8", linewidth=0.8, linestyle="--")
        axis.axhline(0, color="#94a3b8", linewidth=0.6)
        axis.axvline(0, color="#94a3b8", linewidth=0.6)
        axis.legend(fontsize=8)
    else:
        axis.text(0.5, 0.5, "not enough fan transitions", ha="center", va="center")
    axis.set_xlabel("PM2.5 slope before, µg/m³ per minute")
    axis.set_ylabel("PM2.5 slope after, µg/m³ per minute")
    axis.set_title("PM trend around fan-level changes (below the diagonal = falling faster)")
    axis.grid(alpha=0.25)
    figure.tight_layout()
    figure.savefig(path, dpi=140)
    plt.close(figure)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--synthetic", default=None, help="Root produced by generate_air_quality.py")
    parser.add_argument("--real-csv", default=None, help="Optional export of real telemetry")
    parser.add_argument("--output", default="reports/eda")
    parser.add_argument("--episode", default=None, help="Episode used for autocorrelation plots")
    args = parser.parse_args()

    output = Path(args.output)
    figures = output / "figures"
    figures.mkdir(parents=True, exist_ok=True)

    report: dict[str, object] = {}
    frames = []
    if args.synthetic:
        synthetic = load_synthetic(Path(args.synthetic))
        if not synthetic.empty:
            synthetic["source"] = "synthetic"
            frames.append(synthetic)
            report["synthetic"] = {
                "rows": int(len(synthetic)),
                "episodes": int(synthetic["episode_id"].nunique()),
                "scenarios": sorted(synthetic["scenario"].dropna().unique().tolist()),
                "valid_pm_fraction": round(float(synthetic["pm25"].notna().mean()), 4),
                "saturated_fraction": round(float(synthetic["pm25_saturated"].mean()), 4),
            }
    if args.real_csv:
        real = pd.read_csv(args.real_csv)
        real["timestamp"] = pd.to_datetime(real["recorded_at"], utc=True)
        real["episode_id"] = real.get("device_id", "real")
        real["split"] = "real"
        real["source"] = "real"
        for column in ("fan_level", "fan_on", "scenario", "pm25_saturated", "dust_fault"):
            if column not in real:
                real[column] = np.nan
        frames.append(real)

    if not frames:
        raise SystemExit("no data: pass --synthetic and/or --real-csv")

    frame = pd.concat(frames, ignore_index=True, sort=False)
    report["rows"] = int(len(frame))
    report["sources"] = sorted(frame["source"].dropna().unique().tolist())

    coverage = coverage_table(frame)
    coverage.to_csv(output / "coverage.csv", index=False)
    report["coverage"] = {
        "sessions": int(len(coverage)),
        "worst_pm_valid": round(float(coverage["pm_valid"].min()), 4),
        "median_pm_valid": round(float(coverage["pm_valid"].median()), 4),
    }

    intervals = packet_intervals(frame)
    if len(intervals):
        report["packet_interval_seconds"] = {
            "p50": round(float(intervals.median()), 2),
            "p95": round(float(intervals.quantile(0.95)), 2),
            "share_over_10s": round(float((intervals > 10).mean()), 4),
        }

    episode_ids = frame["episode_id"].dropna().unique()
    chosen = args.episode if args.episode in episode_ids else (episode_ids[0] if len(episode_ids) else None)
    if chosen is not None:
        subset = frame[frame["episode_id"] == chosen]
        report["autocorrelation_episode"] = chosen
        report["autocorrelation_pm25"] = plot_acf(subset["pm25"], figures / "acf-pm25.png")
        pairs = {
            name: cross_correlation(subset["pm25"], subset[column])
            for name, column in (("co2", "co2"), ("temperature", "temperature"), ("humidity", "humidity"))
            if column in subset
        }
        report["cross_correlation"] = pairs
        plot_cross_correlation(pairs, figures / "cross-correlation.png")

    # Pooled across sessions: one episode without fan changes must not turn the whole row into NaN.
    correlations = {
        column: result
        for column in ("co2", "temperature", "humidity", "fan_level")
        if (result := safe_correlation(frame, column)) is not None
    }
    report["correlations"] = correlations

    if "fan_level" in frame:
        episodes = fan_episodes(frame.dropna(subset=["pm25"]))
        episodes.to_csv(output / "fan-episodes.csv", index=False)
        report["fan_episodes"] = int(len(episodes))
        if not episodes.empty:
            up = episodes[episodes["to_level"] > episodes["from_level"]]
            report["fan_slope_change"] = {
                "turned_up_sessions": int(len(up)),
                "mean_slope_before": round(float(up["slope_before"].mean()), 3),
                "mean_slope_after": round(float(up["slope_after"].mean()), 3),
                "note": "negative slope means PM falls; the source that triggered the fan may still run",
            }
        plot_fan_episodes(episodes, figures / "fan-episodes.png")

    if len(frame.dropna(subset=["pm25"])):
        plot_coverage(frame, figures / "coverage.png")
        plot_distributions(frame, figures / "distributions.png")

    if "source" in frame.columns and frame["source"].nunique() > 1:
        comparison = frame.groupby("source")[["pm25", "co2"]].describe().round(2)
        comparison.to_csv(output / "real-vs-synthetic.csv")
        report["real_vs_synthetic"] = "see real-vs-synthetic.csv — do not merge the two into one accuracy claim"

    report["limits"] = [
        "Correlation is not causation; the backend turns the fan on when PM rises.",
        "The filter response is a scenario parameter, not a measured CADR.",
        "SSE speed does not mean the sensor samples faster than 5 s.",
        "Synthetic and real results are reported separately, never as one percentage.",
    ]
    (output / "eda.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
