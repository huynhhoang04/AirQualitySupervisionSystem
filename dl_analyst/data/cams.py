from datetime import datetime, timezone

import numpy as np
import pandas as pd

CAMS_LICENSE = "https://atmosphere.copernicus.eu/sites/default/files/repository/CAMS_data_license.pdf"
CAMS_ATTRIBUTION = "Generated using Copernicus Atmosphere Monitoring Service Information"


def pm25_to_ug_m3(values, unit: str) -> np.ndarray:
    normalized = unit.strip().lower().replace("µ", "u").replace("μ", "u").replace(" ", "")
    array = np.asarray(values, dtype=np.float64)
    if normalized in {"kgm-3", "kg/m3", "kgm^-3"}:
        return array * 1_000_000_000
    if normalized in {"ugm-3", "ug/m3", "ugm^-3"}:
        return array
    raise ValueError(f"unsupported CAMS PM2.5 unit: {unit}")


def build_cams_records(values, valid_times, forecast_origin, latitude: float, longitude: float,
                       unit: str, retrieved_at: str | None = None) -> pd.DataFrame:
    converted = pm25_to_ug_m3(values, unit)
    valid_times = pd.to_datetime(valid_times, utc=True)
    origin = pd.Timestamp(forecast_origin)
    if origin.tzinfo is None:
        raise ValueError("forecast_origin must be timezone-aware")
    origin = origin.tz_convert("UTC")
    if len(valid_times) != len(converted):
        raise ValueError("valid_times and values must have equal length")
    if (valid_times < origin).any():
        raise ValueError("CAMS valid time cannot precede its forecast origin")
    return pd.DataFrame({
        "timestamp_utc": valid_times,
        "source_id": "cams_global_forecast",
        "dataset_version": "50r1",
        "site_id": f"grid_{latitude:.4f}_{longitude:.4f}",
        "sensor_id": None,
        "setting": "ambient_outdoor_model",
        "latitude": latitude,
        "longitude": longitude,
        "pm25_ug_m3": converted,
        "pm10_ug_m3": None,
        "co2_ppm": None,
        "co2_kind": None,
        "temperature_c": None,
        "humidity_pct": None,
        "pressure_hpa": None,
        "original_value": np.asarray(values, dtype=np.float64),
        "original_unit": unit,
        "quality_flags": [[] for _ in converted],
        "license_id": CAMS_LICENSE,
        "attribution": f"{CAMS_ATTRIBUTION} {origin.year}.",
        "source_url": "https://ads.atmosphere.copernicus.eu/datasets/cams-global-atmospheric-composition-forecasts",
        "retrieved_at_utc": retrieved_at or datetime.now(timezone.utc).isoformat(),
        "transform_version": "cams-global-v1",
        "forecast_origin_utc": origin,
        "lead_time_hours": (valid_times - origin).total_seconds() / 3600,
    })
