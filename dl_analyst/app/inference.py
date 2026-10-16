import os
from datetime import timedelta
from pathlib import Path

import joblib
import numpy as np

from app.forecast_models import Chronos2Adapter

from app.schemas import (
    DosageResponse,
    ForecastPoint,
    ForecastRequest,
    ForecastResponse,
    ThresholdResponse,
)


PURPOSE_CODES = {"bedroom": 0, "classroom": 1, "office": 2, "living_room": 3, "dorm": 4}
REGION_CODES = {"north": 0, "central": 1, "south": 2, "hanoi": 0, "hanoi_caugiay": 0}
VENTILATION_CODES = {"low": 0, "medium": 1, "high": 2, "unknown": 3}


def encode_threshold_features(request):
    purpose = request.purpose.strip().lower()
    region = request.region.strip().lower()
    if purpose not in PURPOSE_CODES or region not in REGION_CODES:
        raise ValueError("unknown purpose or region category")
    return np.asarray([[
        request.volume_m3,
        PURPOSE_CODES[purpose],
        REGION_CODES[region],
        _ventilation_code(request.ventilation_estimate),
        request.occupancy_typical,
    ]], dtype=np.float64)


def encode_dosage_features(request):
    purpose = request.purpose.strip().lower()
    if purpose not in PURPOSE_CODES:
        raise ValueError("unknown purpose category")
    return np.asarray([[
        request.volume_m3,
        PURPOSE_CODES[purpose],
        request.current_pm25,
        request.base_threshold,
        request.outdoor_pm25_estimate,
        request.filter_cadr,
    ]], dtype=np.float64)


def _ventilation_code(value: str | None) -> int:
    normalized = (value or "unknown").strip().lower()
    if normalized not in VENTILATION_CODES:
        raise ValueError(f"unknown ventilation estimate: {value}")
    return VENTILATION_CODES[normalized]


class JoblibForecastAdapter:
    def __init__(self, model, model_version: str):
        self.model = model
        self.model_version = model_version

    def predict(self, context: np.ndarray, horizon: int):
        values = np.asarray(context, dtype=np.float32).reshape(1, -1)
        quantiles = np.asarray(self.model.predict(values))[0]
        return type("ForecastPrediction", (), {
            "quantiles": quantiles,
            "model_version": self.model_version,
        })()


class ModelRegistry:
    def __init__(self, model_dir: str | None = None, forecast_backend: str | None = None):
        self.model_dir = Path(model_dir or os.getenv("AQSS_MODEL_DIR", "models"))
        self.artifacts = {}
        for name in ("threshold", "dosage"):
            path = self.model_dir / f"{name}.joblib"
            if path.is_file():
                try:
                    artifact = joblib.load(path)
                    if isinstance(artifact, dict) and artifact.get("model_version"):
                        self.artifacts[name] = artifact
                except Exception:
                    continue
        backend = forecast_backend or os.getenv("AQSS_FORECAST_BACKEND", "joblib")
        self.forecast_backend = backend
        self.forecast_model = None
        self.forecast_version = None
        if backend == "chronos2":
            try:
                self.forecast_model = Chronos2Adapter(
                    model_id=os.getenv("AQSS_CHRONOS_MODEL", "amazon/chronos-2"),
                    device=os.getenv("AQSS_CHRONOS_DEVICE", "cpu"),
                )
                self.forecast_version = self.forecast_model.model_version
            except Exception:
                self.forecast_model = None
        elif backend == "joblib":
            path = self.model_dir / "forecast.joblib"
            if path.is_file():
                try:
                    artifact = joblib.load(path)
                    if isinstance(artifact, dict) and artifact.get("model_version"):
                        self.artifacts["forecast"] = artifact
                        self.forecast_model = JoblibForecastAdapter(artifact["model"], artifact["model_version"])
                        self.forecast_version = artifact["model_version"]
                except Exception:
                    self.forecast_model = None
        else:
            raise ValueError(f"unsupported forecast backend: {backend}")

    @property
    def ready(self) -> bool:
        return all(name in self.artifacts for name in ("threshold", "dosage")) and self.forecast_model is not None

    @property
    def model_status(self) -> dict[str, bool]:
        return {
            "threshold": "threshold" in self.artifacts,
            "dosage": "dosage" in self.artifacts,
            "forecast": self.forecast_model is not None,
        }

    def threshold(self, request):
        artifact = self.artifacts.get("threshold")
        if artifact is None:
            return None
        purpose = request.purpose.strip().lower()
        region = request.region.strip().lower()
        purpose_codes = {"bedroom": 0, "classroom": 1, "office": 2, "living_room": 3, "dorm": 4}
        region_codes = {"north": 0, "central": 1, "south": 2, "hanoi": 0, "hanoi_caugiay": 0}
        if purpose not in purpose_codes or region not in region_codes:
            raise ValueError("unknown purpose or region category")
        features = np.asarray([[
            request.volume_m3,
            purpose_codes[purpose],
            region_codes[region],
            _ventilation_code(request.ventilation_estimate),
            request.occupancy_typical,
        ]], dtype=np.float64)
        model = artifact["model"]
        prediction, std = model.predict(features, return_std=True)
        value = float(np.clip(prediction[0], 0, 500))
        uncertainty = float(max(std[0], 0))
        return ThresholdResponse(
            space_id=request.space_id,
            base_threshold_pm25=value,
            uncertainty_lower=max(0, value - 1.96 * uncertainty),
            uncertainty_upper=min(500, value + 1.96 * uncertainty),
            model_version=artifact["model_version"],
        )

    def dosage(self, request):
        artifact = self.artifacts.get("dosage")
        if artifact is None:
            return None
        purpose = request.purpose.strip().lower()
        purpose_codes = {"bedroom": 0, "classroom": 1, "office": 2, "living_room": 3, "dorm": 4}
        if purpose not in purpose_codes:
            raise ValueError("unknown purpose category")
        features = np.asarray([[
            request.volume_m3,
            purpose_codes[purpose],
            request.current_pm25,
            request.base_threshold,
            request.outdoor_pm25_estimate,
            request.filter_cadr,
        ]], dtype=np.float64)
        level = int(np.clip(round(float(artifact["level_model"].predict(features)[0])), 0, 3))
        duration = int(np.clip(round(float(artifact["duration_model"].predict(features)[0])), 1, 120))
        return DosageResponse(
            space_id=request.space_id,
            filter_level=level,
            run_duration_minutes=duration,
            model_version=artifact["model_version"],
        )

    def forecast(self, request: ForecastRequest):
        if self.forecast_model is None:
            return None
        series = np.asarray([point.pm25 for point in request.series], dtype=np.float32)
        output = self.forecast_model.predict(series, 96)
        quantiles = np.asarray(output.quantiles, dtype=np.float32)
        model_version = output.model_version
        if quantiles.shape != (96, 3) or not np.isfinite(quantiles).all():
            return None
        quantiles = np.maximum.accumulate(np.clip(quantiles, 0, 500), axis=1)
        last_time = request.series[-1].timestamp
        points = [
            ForecastPoint(
                target_time=last_time + timedelta(minutes=15 * (index + 1)),
                pm25_q10=float(row[0]),
                pm25_q50=float(row[1]),
                pm25_q90=float(row[2]),
            )
            for index, row in enumerate(quantiles)
        ]
        return ForecastResponse(
            space_id=request.space_id,
            status="ok",
            model_version=model_version,
            forecast_sequence=points,
        )
