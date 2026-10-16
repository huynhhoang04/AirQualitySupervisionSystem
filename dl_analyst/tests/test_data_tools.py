import pandas as pd

from data.audit_dataset import audit
from data.forecast_windows import make_windows, resample_pm25, temporal_split


def test_audit_reports_invalid_rows_and_duplicates(tmp_path):
    path = tmp_path / "input.csv"
    pd.DataFrame({
        "timestamp": ["2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "bad"],
        "pm25": [10, 12, 9],
    }).to_csv(path, index=False)
    result = audit(path, "timestamp", "pm25")
    assert result["duplicate_timestamps"] == 1
    assert result["invalid_timestamp_or_value"] == 1


def test_resample_windows_and_splits_are_chronological():
    timestamps = pd.date_range("2026-01-01", periods=1600, freq="15min", tz="UTC")
    frame = pd.DataFrame({"timestamp": timestamps, "pm25": [value % 200 for value in range(1600)]})
    values = resample_pm25(frame)
    train, validation, test = temporal_split(values, 96, 96)
    assert train[0].shape == (929, 96)
    assert validation[0].shape == (49, 96)
    assert test[0].shape == (49, 96)
    assert train[1][-1, -1] < validation[0][0, 0]
    assert validation[1][-1, -1] < test[0][0, 0]


def test_missing_bucket_is_not_filled_or_used_in_windows():
    timestamps = pd.date_range("2026-01-01", periods=300, freq="15min", tz="UTC").delete(150)
    frame = pd.DataFrame({"timestamp": timestamps, "pm25": 12.0})
    values = resample_pm25(frame)
    assert pd.isna(values.iloc[150])
    x, y = make_windows(values, 96, 96)
    assert len(x) == 0
    assert len(y) == 0
