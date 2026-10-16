import numpy as np

from app.forecast_models import PersistenceBaseline, SeasonalNaiveBaseline
from training.backtest import evaluate_series, score


def test_baselines_return_ordered_quantiles_with_expected_shape():
    context = np.arange(96, dtype=np.float32)
    for model in (PersistenceBaseline(), SeasonalNaiveBaseline(24)):
        output = model.predict(context, 48)
        assert output.quantiles.shape == (48, 3)
        assert np.isfinite(output.quantiles).all()
        assert np.all(output.quantiles[:, 0] <= output.quantiles[:, 1])
        assert np.all(output.quantiles[:, 1] <= output.quantiles[:, 2])


def test_score_reports_errors_and_quantile_metrics():
    actual = np.array([10.0, 20.0])
    quantiles = np.array([[8.0, 10.0, 12.0], [18.0, 21.0, 25.0]])
    result = score(actual, quantiles)
    assert result["mae"] == 0.5
    assert result["interval_80_coverage"] == 1.0
    assert result["pinball_q50"] >= 0


def test_backtest_drops_origins_crossing_missing_data():
    class RecordingBaseline:
        calls = 0

        def predict(self, context, horizon):
            self.calls += 1
            assert np.isfinite(context).all()
            assert np.isfinite(context[-1])
            return PersistenceBaseline().predict(context, horizon)

    values = np.arange(240, dtype=np.float32)
    values[170] = np.nan
    model = RecordingBaseline()
    result = evaluate_series(values, 48, 12, 10, {"recording": model})
    assert result["evaluated_origins"] == model.calls
    assert result["evaluated_origins"] > 0
