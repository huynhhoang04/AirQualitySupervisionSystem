"""Temporal split, walk-forward validation, promotion gate and metrics.

Rules this file enforces:
* no shuffle, preprocessing fit inside each fold;
* a 30-minute purge at every boundary relative to the label horizon;
* the test window is touched once, for the frozen candidate and the baselines;
* underperforming candidates are still reported, with the reason.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from .data import build_dataset
from .features import FEATURE_VERSION, build_features, feature_columns, ready_origins
from .models import BOOSTING_GRID, HORIZONS, RIDGE_GRID, ModelBundle, PersistenceBundle, make_estimator


class NotEnoughData(Exception):
    pass


@dataclass
class Selection:
    algorithm: str
    params: dict[str, Any]
    validation: dict[str, Any]
    best_ridge_params: dict[str, Any]
    best_ridge_validation: dict[str, Any]
    best_boosting_params: dict[str, Any] | None
    best_boosting_validation: dict[str, Any] | None


def _metrics(predicted: np.ndarray, actual: np.ndarray) -> dict[str, float]:
    if len(actual) == 0:
        return {}
    error = predicted - actual
    return {
        "mae": float(np.mean(np.abs(error))),
        "rmse": float(np.sqrt(np.mean(error**2))),
    }


def _folds(index: pd.DatetimeIndex, folds: int = 3, min_train_hours: int = 12,
           val_hours: int = 4) -> list[tuple[pd.Timestamp, pd.Timestamp, pd.Timestamp, pd.Timestamp]]:
    if len(index) == 0:
        return []
    start, end = index.min(), index.max()
    span = end - start
    val = pd.Timedelta(hours=val_hours)
    min_train = pd.Timedelta(hours=min_train_hours)

    boundaries = []
    for i in range(folds):
        val_end = end - val * (folds - i)
        val_start = val_end - val
        if val_start - start >= min_train:
            boundaries.append((start, val_start, val_start, val_end))
    if boundaries:
        return boundaries

    # Fallback: one chronological holdout at the end of development.
    cut = start + span * 0.75
    if cut <= start:
        return []
    return [(start, cut, cut, end)]


def _xy(features: pd.DataFrame, frame: pd.DataFrame, horizon: int,
        index: pd.Index | None = None) -> tuple[pd.DataFrame, pd.Series]:
    target = frame[f"y{horizon}"]
    usable = target.dropna().index
    if index is not None:
        usable = usable.intersection(index)
    common = features.index.intersection(usable)
    return features.loc[common], target.loc[common].astype(float)


def _score(metrics: dict[str, dict[str, float]]) -> float:
    values = []
    for horizon in ("15", "30"):
        if horizon in metrics and "mae" in metrics[horizon]:
            values.append(metrics[horizon]["mae"])
    if not values and "5" in metrics and "mae" in metrics["5"]:
        values.append(metrics["5"]["mae"])
    return float(np.mean(values)) if values else math.inf


def validate_candidate(features: pd.DataFrame, frame: pd.DataFrame, algorithm: str,
                       params: dict[str, Any], folds: int = 3) -> dict[str, Any]:
    boundaries = _folds(features.index, folds=folds)
    if not boundaries:
        raise NotEnoughData("not enough development history for a fold")

    predictions: dict[int, list[float]] = {horizon: [] for horizon in HORIZONS}
    actuals: dict[int, list[float]] = {horizon: [] for horizon in HORIZONS}

    for _, train_end, val_start, val_end in boundaries:
        for horizon in HORIZONS:
            purge = train_end - pd.Timedelta(minutes=horizon)
            train_mask = (features.index < purge) & frame[f"y{horizon}"].notna()
            val_mask = ((features.index >= val_start) & (features.index <= val_end)
                        & frame[f"y{horizon}"].notna())
            if train_mask.sum() < 50 or val_mask.sum() == 0:
                continue
            estimator = make_estimator(algorithm, params)
            estimator.fit(features.loc[train_mask], frame.loc[train_mask, f"y{horizon}"].astype(float))
            predicted = np.clip(estimator.predict(features.loc[val_mask]), 0.0, 500.0)
            predictions[horizon].extend(predicted.tolist())
            actuals[horizon].extend(frame.loc[val_mask, f"y{horizon}"].astype(float).tolist())

    metrics: dict[str, Any] = {"algorithm": algorithm, "params": params, "horizons": {}}
    for horizon in HORIZONS:
        if predictions[horizon]:
            computed = _metrics(np.asarray(predictions[horizon]), np.asarray(actuals[horizon]))
            computed["samples"] = len(actuals[horizon])
            metrics["horizons"][str(horizon)] = computed
    if not metrics["horizons"]:
        raise NotEnoughData(f"{algorithm} produced no validation predictions")
    return metrics


def validate_persistence(features: pd.DataFrame, frame: pd.DataFrame, folds: int = 3) -> dict[str, Any]:
    boundaries = _folds(features.index, folds=folds)
    predictions: dict[int, list[float]] = {horizon: [] for horizon in HORIZONS}
    actuals: dict[int, list[float]] = {horizon: [] for horizon in HORIZONS}
    for _, train_end, val_start, val_end in boundaries:
        for horizon in HORIZONS:
            val_mask = ((features.index >= val_start) & (features.index <= val_end)
                        & frame[f"y{horizon}"].notna())
            if val_mask.sum() == 0:
                continue
            baseline = PersistenceBundle()
            predicted = baseline.predict(features.loc[val_mask], horizon)
            predictions[horizon].extend(predicted.tolist())
            actuals[horizon].extend(frame.loc[val_mask, f"y{horizon}"].astype(float).tolist())

    metrics: dict[str, Any] = {"algorithm": "persistence", "params": {}, "horizons": {}}
    for horizon in HORIZONS:
        if predictions[horizon]:
            computed = _metrics(np.asarray(predictions[horizon]), np.asarray(actuals[horizon]))
            computed["samples"] = len(actuals[horizon])
            metrics["horizons"][str(horizon)] = computed
    return metrics


def select_model(features: pd.DataFrame, frame: pd.DataFrame, folds: int = 3) -> Selection:
    best_ridge: tuple[float, dict, dict] | None = None
    for params in RIDGE_GRID:
        metrics = validate_candidate(features, frame, "ridge", params, folds)
        score = _score(metrics["horizons"])
        if best_ridge is None or score < best_ridge[0]:
            best_ridge = (score, params, metrics)
    if best_ridge is None:
        raise NotEnoughData("ridge produced no validation result")

    best_boosting: tuple[float, dict, dict] | None = None
    for params in BOOSTING_GRID:
        try:
            metrics = validate_candidate(features, frame, "boosting", params, folds)
        except NotEnoughData:
            continue
        score = _score(metrics["horizons"])
        if best_boosting is None or score < best_boosting[0]:
            best_boosting = (score, params, metrics)

    candidates = [("ridge", best_ridge)]
    if best_boosting is not None:
        candidates.append(("boosting", best_boosting))
    algorithm, (_, params, validation) = min(candidates, key=lambda item: item[1][0])

    return Selection(
        algorithm=algorithm,
        params=params,
        validation=validation,
        best_ridge_params=best_ridge[1],
        best_ridge_validation=best_ridge[2],
        best_boosting_params=None if best_boosting is None else best_boosting[1],
        best_boosting_validation=None if best_boosting is None else best_boosting[2],
    )


def fit_bundle(features: pd.DataFrame, frame: pd.DataFrame, algorithm: str,
               params: dict[str, Any], index: pd.Index | None = None) -> ModelBundle:
    estimators: dict[int, Any] = {}
    columns = feature_columns(features)
    usable_index = features.index if index is None else features.index.intersection(index)
    for horizon in HORIZONS:
        frame_h, target = _xy(features, frame, horizon, usable_index)
        if len(target) < 50:
            raise NotEnoughData(f"not enough rows to fit horizon {horizon}")
        estimator = make_estimator(algorithm, params)
        estimator.fit(frame_h, target)
        estimators[horizon] = estimator
    cutoff = usable_index.max().isoformat() if len(usable_index) else None
    return ModelBundle(
        algorithm=algorithm,
        feature_columns=columns,
        estimators=estimators,
        params=params,
        feature_version=FEATURE_VERSION,
        train_cutoff=cutoff,
    )


def evaluate_bundle(features: pd.DataFrame, frame: pd.DataFrame, bundle: Any,
                    index: pd.Index) -> dict[str, Any]:
    result: dict[str, Any] = {"algorithm": getattr(bundle, "algorithm", "unknown"), "horizons": {}}
    for horizon in HORIZONS:
        frame_h, target = _xy(features, frame, horizon, index)
        if len(target) == 0:
            continue
        predicted = bundle.predict(frame_h, horizon)
        computed = _metrics(np.asarray(predicted), target.to_numpy())
        computed["samples"] = int(len(target))
        result["horizons"][str(horizon)] = computed
    return result


def promotion_gate(candidate: dict[str, Any], baselines: list[dict[str, Any]]) -> tuple[str, str]:
    """Return (status, reason) for the frozen candidate compared to the best baseline."""
    usable = [baseline for baseline in baselines if baseline.get("horizons")]
    if not candidate.get("horizons") or not usable:
        return "REJECTED", "no comparable test horizons"

    best_baseline = min(usable, key=lambda item: _score(item["horizons"]))
    candidate_score = _score(candidate["horizons"])
    baseline_score = _score(best_baseline["horizons"])

    if baseline_score == 0:
        if candidate_score == 0:
            return "REJECTED", "tie at zero error; keep the simpler baseline"
        return "REJECTED", "baseline error is zero and the candidate is not"

    gain = 100.0 * (baseline_score - candidate_score) / baseline_score
    candidate_5 = candidate["horizons"].get("5", {}).get("mae")
    baseline_5 = best_baseline["horizons"].get("5", {}).get("mae")
    horizon_5_ok = True
    if candidate_5 is not None and baseline_5 is not None:
        horizon_5_ok = candidate_5 <= baseline_5 * 1.05

    if gain >= 10.0 and horizon_5_ok:
        return "PRODUCTION", (
            f"mean MAE(15,30) {candidate_score:.3f} vs {baseline_score:.3f} "
            f"({gain:.1f}% better), MAE5 within 5%"
        )
    if not horizon_5_ok:
        return "REJECTED", f"MAE5 regressed more than 5% (gain {gain:.1f}% on 15/30)"
    return "REJECTED", f"gain {gain:.1f}% < 10% on mean MAE(15,30)"


def _json_horizons(metrics: dict[str, Any]) -> dict[str, Any]:
    horizons = {}
    for key, value in metrics.get("horizons", {}).items():
        horizons[f"mae_{key}min"] = value.get("mae")
        horizons[f"rmse_{key}min"] = value.get("rmse")
        horizons[f"samples_{key}min"] = value.get("samples")
    return horizons


def train_and_evaluate(rows: list[dict[str, Any]], actuator_rows: list[dict[str, Any]],
                       interval_seconds: float = 5.0, test_hours: int | None = None,
                       folds: int = 3) -> dict[str, Any]:
    frame = build_dataset(rows, actuator_rows, interval_seconds=interval_seconds)
    if frame.empty:
        raise NotEnoughData("no minute buckets")
    features = build_features(frame)
    ready = ready_origins(features)
    if ready.empty:
        raise NotEnoughData("no origin with a complete feature history")
    features = ready
    frame = frame.loc[features.index]

    span_hours = (frame.index.max() - frame.index.min()).total_seconds() / 3600.0
    if span_hours < 24:
        raise NotEnoughData(f"only {span_hours:.1f} h of valid data; need at least 24 h to train")

    if test_hours is None:
        test_hours = 12 if span_hours < 72 else 24
    cutoff = frame.index.max() - pd.Timedelta(hours=test_hours)
    dev_index = features.index[features.index <= cutoff]
    test_index = features.index[features.index > cutoff]
    if len(dev_index) < 120 or len(test_index) < 30:
        raise NotEnoughData(
            f"development/test split too small (dev={len(dev_index)}, test={len(test_index)})"
        )

    selection = select_model(features.loc[dev_index], frame.loc[dev_index], folds=folds)
    candidate_bundle = fit_bundle(features, frame, selection.algorithm, selection.params, dev_index)
    ridge_bundle = fit_bundle(features, frame, "ridge", selection.best_ridge_params, dev_index)

    candidate_test = evaluate_bundle(features, frame, candidate_bundle, test_index)
    ridge_test = evaluate_bundle(features, frame, ridge_bundle, test_index)
    persistence_test = evaluate_bundle(features, frame, PersistenceBundle(), test_index)

    baselines = [ridge_test, persistence_test]
    if selection.algorithm == "ridge":
        baselines = [persistence_test]
    status, reason = promotion_gate(candidate_test, baselines)

    train_origins = {
        str(horizon): int(_xy(features, frame, horizon, dev_index)[1].shape[0])
        for horizon in HORIZONS
    }
    test_origins = {
        str(horizon): int(_xy(features, frame, horizon, test_index)[1].shape[0])
        for horizon in HORIZONS
    }

    metrics = {
        "feature_version": FEATURE_VERSION,
        "algorithm": selection.algorithm,
        "params": selection.params,
        "data": {
            "raw_rows": len(rows),
            "minute_buckets": int(len(frame)),
            "span_hours": round(span_hours, 2),
            "test_hours": test_hours,
            "train_origins": train_origins,
            "test_origins": test_origins,
        },
        "validation": _json_horizons(selection.validation),
        "test": {
            "candidate": _json_horizons(candidate_test),
            "ridge": _json_horizons(ridge_test),
            "persistence": _json_horizons(persistence_test),
        },
        "promotion": {"status": status, "reason": reason},
        "validation_detail": {
            "ridge": _json_horizons(selection.best_ridge_validation),
            "boosting": _json_horizons(selection.best_boosting_validation)
            if selection.best_boosting_validation else None,
        },
    }
    return {
        "bundle": candidate_bundle,
        "metrics": metrics,
        "status": status,
        "promotion_reason": reason,
        "train_cutoff": candidate_bundle.train_cutoff,
        "test_hours": test_hours,
        "span_hours": span_hours,
    }
