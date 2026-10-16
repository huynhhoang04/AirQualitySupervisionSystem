from datetime import datetime, timedelta
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class ThresholdRequest(BaseModel):
    space_id: str
    volume_m3: float = Field(gt=0, le=10000)
    purpose: str
    region: str
    ventilation_estimate: str | None = None
    occupancy_typical: int = Field(gt=0, le=10000)


class ThresholdResponse(BaseModel):
    space_id: str
    base_threshold_pm25: float = Field(ge=0, le=500)
    uncertainty_lower: float = Field(ge=0, le=500)
    uncertainty_upper: float = Field(ge=0, le=500)
    model_version: str

    @model_validator(mode="after")
    def ordered_bounds(self):
        if not self.uncertainty_lower <= self.base_threshold_pm25 <= self.uncertainty_upper:
            raise ValueError("uncertainty bounds must contain the base threshold")
        return self


class DosageRequest(BaseModel):
    space_id: str
    volume_m3: float = Field(gt=0, le=10000)
    purpose: str
    current_pm25: float = Field(ge=0, le=750)
    base_threshold: float = Field(ge=0, le=500)
    outdoor_pm25_estimate: float = Field(ge=0, le=750)
    filter_cadr: float = Field(gt=0, le=10000)


class DosageResponse(BaseModel):
    space_id: str
    filter_level: int = Field(ge=0, le=3)
    run_duration_minutes: int = Field(ge=1, le=120)
    model_version: str


class SeriesPoint(BaseModel):
    timestamp: datetime
    pm25: float = Field(ge=0, le=500)

    @field_validator("timestamp")
    @classmethod
    def timestamp_must_be_timezone_aware(cls, timestamp):
        if timestamp.tzinfo is None or timestamp.utcoffset() is None:
            raise ValueError("timestamp must include a timezone")
        return timestamp


class ForecastRequest(BaseModel):
    space_id: str
    series: list[SeriesPoint] = Field(min_length=96, max_length=96)

    @field_validator("series")
    @classmethod
    def timestamps_are_ordered(cls, series):
        timestamps = [point.timestamp for point in series]
        if any(current - previous != timedelta(minutes=15) for previous, current in zip(timestamps, timestamps[1:])):
            raise ValueError("series timestamps must be spaced exactly 15 minutes apart")
        return series


class ForecastPoint(BaseModel):
    target_time: datetime
    pm25_q50: float = Field(ge=0, le=500)
    pm25_q10: float = Field(ge=0, le=500)
    pm25_q90: float = Field(ge=0, le=500)

    @model_validator(mode="after")
    def ordered_quantiles(self):
        if not self.pm25_q10 <= self.pm25_q50 <= self.pm25_q90:
            raise ValueError("quantiles must satisfy q10 <= q50 <= q90")
        return self


class ForecastResponse(BaseModel):
    space_id: str
    status: Literal["ok"]
    model_version: str
    forecast_sequence: list[ForecastPoint] = Field(min_length=96, max_length=96)
