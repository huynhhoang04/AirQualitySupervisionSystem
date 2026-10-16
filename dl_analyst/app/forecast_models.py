from dataclasses import dataclass
from typing import Protocol

import numpy as np


@dataclass(frozen=True)
class ForecastOutput:
    quantiles: np.ndarray
    model_version: str


class ForecastModel(Protocol):
    @property
    def model_version(self) -> str: ...

    def predict(self, context: np.ndarray, horizon: int) -> ForecastOutput: ...


class Chronos2Adapter:
    def __init__(self, model_id: str = "amazon/chronos-2", device: str = "cpu"):
        from chronos import Chronos2Pipeline

        self._model_id = model_id
        self._pipeline = Chronos2Pipeline.from_pretrained(model_id, device_map=device)

    @property
    def model_version(self) -> str:
        return self._model_id

    def predict(self, context: np.ndarray, horizon: int) -> ForecastOutput:
        values = np.asarray(context, dtype=np.float32).reshape(-1)
        if len(values) == 0 or not np.isfinite(values).all():
            raise ValueError("forecast context must be a non-empty finite series")
        import pandas as pd

        timestamps = pd.date_range("2000-01-01", periods=len(values), freq="h", tz="UTC")
        frame = pd.DataFrame({"id": "aqss", "timestamp": timestamps, "target": values})
        output = self._pipeline.predict_df(
            frame,
            prediction_length=horizon,
            quantile_levels=[0.1, 0.5, 0.9],
            id_column="id",
            timestamp_column="timestamp",
            target="target",
        )
        quantile_columns = ["0.1", "0.5", "0.9"]
        if not all(column in output for column in quantile_columns):
            raise RuntimeError(f"Chronos-2 did not return requested quantiles: {list(output.columns)}")
        quantiles = output[quantile_columns].to_numpy(dtype=np.float32)
        if quantiles.shape != (horizon, 3) or not np.isfinite(quantiles).all():
            raise RuntimeError(f"unexpected Chronos-2 output shape {quantiles.shape}")
        quantiles = np.maximum.accumulate(np.clip(quantiles, 0, 500), axis=1)
        return ForecastOutput(quantiles, self.model_version)


class PersistenceBaseline:
    model_version = "baseline-persistence-v1"

    def predict(self, context: np.ndarray, horizon: int) -> ForecastOutput:
        values = np.asarray(context, dtype=np.float32).reshape(-1)
        if len(values) == 0 or not np.isfinite(values).all():
            raise ValueError("forecast context must be a non-empty finite series")
        median = float(values[-1])
        spread = float(np.nanmedian(np.abs(np.diff(values)))) if len(values) > 1 else 0.0
        steps = np.arange(1, horizon + 1, dtype=np.float32)
        sigma = max(spread, 1.0) * np.sqrt(steps)
        quantiles = np.column_stack((median - 1.2816 * sigma, np.full(horizon, median), median + 1.2816 * sigma))
        quantiles = np.maximum.accumulate(np.clip(quantiles, 0, 500), axis=1)
        return ForecastOutput(quantiles, self.model_version)


class SeasonalNaiveBaseline:
    model_version = "baseline-seasonal-naive-v1"

    def __init__(self, season_length: int):
        self.season_length = season_length

    def predict(self, context: np.ndarray, horizon: int) -> ForecastOutput:
        values = np.asarray(context, dtype=np.float32).reshape(-1)
        if len(values) < self.season_length or not np.isfinite(values).all():
            raise ValueError("context must be finite and at least one season long")
        median = np.resize(values[-self.season_length:], horizon)
        spread = float(np.nanmedian(np.abs(np.diff(values))))
        sigma = max(spread, 1.0) * np.sqrt(np.arange(1, horizon + 1, dtype=np.float32))
        quantiles = np.column_stack((median - 1.2816 * sigma, median, median + 1.2816 * sigma))
        quantiles = np.maximum.accumulate(np.clip(quantiles, 0, 500), axis=1)
        return ForecastOutput(quantiles, self.model_version)
