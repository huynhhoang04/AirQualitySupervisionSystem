from pathlib import Path

import pandas as pd

from data.uci_adapters import adapt_uci_381, adapt_uci_501


def test_uci_381_maps_hourly_beijing_rows_to_utc_and_flags_high_pm25(tmp_path):
    raw = pd.DataFrame({
        "year": [2010, 2010], "month": [1, 1], "day": [1, 1], "hour": [0, 1],
        "pm2.5": [20, 610], "TEMP": [-5.0, -4.5], "PRES": [1020, 1021],
    })
    result = adapt_uci_381(raw, tmp_path / "source.zip", "2026-10-16T00:00:00Z")
    assert result["timestamp_utc"].iloc[0] == pd.Timestamp("2009-12-31T16:00:00Z")
    assert result["site_id"].unique().tolist() == ["us_embassy_beijing"]
    assert result["pm25_ug_m3"].tolist() == [20, 610]
    assert "above_aqss_sensor_valid_range" in result["quality_flags"].iloc[1]
    assert result["temperature_c"].tolist() == [-5.0, -4.5]


def test_uci_501_keeps_station_identity_and_missing_target(tmp_path):
    raw = pd.DataFrame({
        "year": [2013, 2013], "month": [3, 3], "day": [1, 1], "hour": [0, 1],
        "PM2.5": [None, 30], "PM10": [40, 50], "TEMP": [1.0, 1.5],
        "PRES": [1010, 1011], "station": ["Aotizhongxin", "Aotizhongxin"],
    })
    result = adapt_uci_501(raw, tmp_path / "source.zip", "2026-10-16T00:00:00Z")
    assert result["site_id"].unique().tolist() == ["Aotizhongxin"]
    assert pd.isna(result["pm25_ug_m3"].iloc[0])
    assert "missing" in result["quality_flags"].iloc[0]
    assert result["pm10_ug_m3"].tolist() == [40, 50]
