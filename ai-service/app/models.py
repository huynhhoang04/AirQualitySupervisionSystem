"""Model wrappers: persistence baseline, Ridge and gradient boosting.

One estimator per horizon. A +5 minute prediction is never reused as an input for +15/+30.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from .features import FEATURE_VERSION

HORIZONS = (5, 15, 30)


@dataclass
class ModelBundle:
    algorithm: str
    feature_columns: list[str]
    estimators: dict[int, Any]
    params: dict[str, Any] = field(default_factory=dict)
    feature_version: str = FEATURE_VERSION
    train_cutoff: str | None = None

    def predict(self, features: pd.DataFrame, horizon: int) -> np.ndarray:
        estimator = self.estimators[int(horizon)]
        values = estimator.predict(features[self.feature_columns])
        return np.clip(np.asarray(values, dtype=float), 0.0, 500.0)

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        joblib.dump(self, path)

    @staticmethod
    def load(path: Path) -> "ModelBundle":
        bundle = joblib.load(path)
        if not isinstance(bundle, ModelBundle):
            raise TypeError(f"not a ModelBundle: {path}")
        return bundle


@dataclass
class PersistenceBundle:
    """y_hat(t+h) = PM(t). A strong baseline in a slowly changing room."""

    algorithm: str = "persistence"
    feature_version: str = FEATURE_VERSION

    def predict(self, features: pd.DataFrame, horizon: int) -> np.ndarray:
        return np.clip(features["pm_now"].to_numpy(dtype=float), 0.0, 500.0)


def make_ridge(alpha: float) -> Pipeline:
    return Pipeline(
        [
            ("imputer", SimpleImputer(strategy="median", add_indicator=True)),
            ("scaler", StandardScaler()),
            ("ridge", Ridge(alpha=alpha, solver="svd")),
        ]
    )


def make_boosting(params: dict[str, Any]) -> HistGradientBoostingRegressor:
    defaults = dict(
        loss="squared_error",
        learning_rate=0.05,
        max_leaf_nodes=15,
        max_iter=200,
        min_samples_leaf=20,
        l2_regularization=1.0,
        early_stopping=False,
        random_state=42,
        # A small bin count keeps the estimator usable on the short histories this project has.
        max_bins=32,
    )
    defaults.update(params)
    return HistGradientBoostingRegressor(**defaults)


class BoostingModel:
    """Boosting with the all-missing columns dropped.

    HistGradientBoostingRegressor fails on a feature column that is entirely NaN (what a training
    window without actuator reports looks like). Dropping those columns — and remembering the
    decision so inference selects the same ones — keeps the estimator honest instead of inventing
    a fan state.
    """

    def __init__(self, params: dict[str, Any]):
        self.params = params
        self.columns: list[str] | None = None
        self.model = make_boosting(params)

    def fit(self, X: pd.DataFrame, y: pd.Series) -> "BoostingModel":
        self.columns = [column for column in X.columns if X[column].notna().any()]
        self.model.fit(X[self.columns], y)
        return self

    def predict(self, X: pd.DataFrame):
        columns = self.columns if self.columns is not None else list(X.columns)
        return self.model.predict(X[columns])


class DeltaRegressor:
    """Learn the change from the measured anchor, instead of regressing room levels only."""

    def __init__(self, estimator: Any):
        self.estimator = estimator

    def fit(self, X: pd.DataFrame, y: pd.Series) -> "DeltaRegressor":
        self.estimator.fit(X, y - X["pm_now"])
        return self

    def predict(self, X: pd.DataFrame) -> np.ndarray:
        return X["pm_now"].to_numpy(dtype=float) + self.estimator.predict(X)


def make_estimator(algorithm: str, params: dict[str, Any]):
    params = dict(params)
    delta = params.pop("target_delta", False)
    if algorithm == "ridge":
        estimator = make_ridge(float(params.get("alpha", 10.0)))
    elif algorithm == "boosting":
        estimator = BoostingModel(params)
    else:
        raise ValueError(f"unknown algorithm: {algorithm}")
    return DeltaRegressor(estimator) if delta else estimator


RIDGE_GRID = [{"alpha": alpha, "target_delta": delta} for alpha in (0.1, 1.0, 10.0, 100.0) for delta in (False, True)]
BOOSTING_GRID = [
    {"learning_rate": rate, "max_leaf_nodes": leaves, "target_delta": delta}
    for rate in (0.05, 0.1)
    for leaves in (7, 15)
    for delta in (False, True)
]
