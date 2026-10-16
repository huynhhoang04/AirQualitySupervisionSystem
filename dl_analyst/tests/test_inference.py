from datetime import datetime, timedelta, timezone

import numpy as np

from app.inference import ModelRegistry
from app.schemas import ForecastRequest, SeriesPoint


class StubForecast:
    model_version = "stub-v1"

    def predict(self, context, horizon):
        assert context.shape == (96,)
        assert horizon == 96
        return type("Output", (), {
            "quantiles": np.tile(np.array([12.0, 10.0, 14.0], dtype=np.float32), (96, 1)),
            "model_version": self.model_version,
        })()


def test_forecast_registry_normalizes_quantile_crossing_and_emits_contract_points():
    registry = ModelRegistry(model_dir="/missing-model-dir")
    registry.forecast_model = StubForecast()
    registry.forecast_version = "stub-v1"
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    request = ForecastRequest(
        space_id="SP_201",
        series=[
            SeriesPoint(timestamp=start + timedelta(minutes=15 * index), pm25=10)
            for index in range(96)
        ],
    )

    result = registry.forecast(request)

    assert result.model_version == "stub-v1"
    assert len(result.forecast_sequence) == 96
    assert result.forecast_sequence[0].pm25_q10 == 12
    assert result.forecast_sequence[0].pm25_q50 == 12
    assert result.forecast_sequence[0].pm25_q90 == 14
