"""Pydantic schemas — the wire contract of `docs/io_contract.md` §8, exactly.

Bounds are the shared sensor table (§9): a value the node or backend would reject must not reach a
model, and a value the model returns must be one the dashboard can display.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

# io_contract §9. One table, three layers.
PM25_MIN, PM25_MAX = 0.0, 750.0     # 750 = GP2Y1023 saturation; 500 = reporting ceiling
PM25_REPORT_MAX = 500.0
STEPS = 96                          # 24 h @ 15 min
STEP = timedelta(minutes=15)


class ThresholdRequest(BaseModel):
    space_id: str
    volume_m3: float = Field(gt=0, le=10000)
    purpose: str
    region: str
    ventilation_estimate: str | None = None
    occupancy_typical: int = Field(ge=0, le=10000)


class ThresholdResponse(BaseModel):
    space_id: str
    base_threshold_pm25: float = Field(ge=0, le=PM25_REPORT_MAX)
    uncertainty_lower: float = Field(ge=0, le=PM25_REPORT_MAX)
    uncertainty_upper: float = Field(ge=0, le=PM25_REPORT_MAX)
    model_version: str

    @model_validator(mode="after")
    def ordered_bounds(self) -> "ThresholdResponse":
        if not self.uncertainty_lower <= self.base_threshold_pm25 <= self.uncertainty_upper:
            raise ValueError("uncertainty bounds must contain the base threshold")
        return self


class DosageRequest(BaseModel):
    space_id: str
    volume_m3: float = Field(gt=0, le=10000)
    purpose: str
    current_pm25: float = Field(ge=PM25_MIN, le=PM25_MAX)
    base_threshold: float = Field(gt=0, le=PM25_REPORT_MAX)
    outdoor_pm25_estimate: float = Field(ge=PM25_MIN, le=PM25_MAX)
    filter_cadr: float = Field(gt=0, le=10000)


class DosageResponse(BaseModel):
    space_id: str
    filter_level: int = Field(ge=0, le=3)          # maps to PWM 0/35/60/90 %
    run_duration_minutes: int = Field(ge=1, le=120)
    model_version: str


class SeriesPoint(BaseModel):
    timestamp: datetime
    pm25: float = Field(ge=PM25_MIN, le=PM25_MAX)

    @field_validator("timestamp")
    @classmethod
    def timestamps_are_aware(cls, timestamp: datetime) -> datetime:
        if timestamp.tzinfo is None or timestamp.utcoffset() is None:
            raise ValueError("timestamp must include a timezone")
        return timestamp


class ForecastRequest(BaseModel):
    space_id: str
    series: list[SeriesPoint] = Field(min_length=STEPS, max_length=STEPS)

    @field_validator("series")
    @classmethod
    def timestamps_are_evenly_spaced(cls, series: list[SeriesPoint]) -> list[SeriesPoint]:
        for previous, current in zip(series, series[1:]):
            if current.timestamp - previous.timestamp != STEP:
                raise ValueError("series timestamps must be spaced exactly 15 minutes apart")
        return series


class ForecastPoint(BaseModel):
    target_time: datetime
    pm25_q10: float = Field(ge=0, le=PM25_REPORT_MAX)
    pm25_q50: float = Field(ge=0, le=PM25_REPORT_MAX)
    pm25_q90: float = Field(ge=0, le=PM25_REPORT_MAX)

    @model_validator(mode="after")
    def ordered_quantiles(self) -> "ForecastPoint":
        if not self.pm25_q10 <= self.pm25_q50 <= self.pm25_q90:
            raise ValueError("quantiles must satisfy q10 <= q50 <= q90")
        return self


class ForecastResponse(BaseModel):
    space_id: str
    status: Literal["ok"]
    model_version: str
    forecast_sequence: list[ForecastPoint] = Field(min_length=STEPS, max_length=STEPS)
