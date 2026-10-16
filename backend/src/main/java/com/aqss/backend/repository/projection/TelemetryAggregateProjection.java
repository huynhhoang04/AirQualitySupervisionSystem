package com.aqss.backend.repository.projection;

import java.time.Instant;

/** Result of a {@code date_trunc} aggregate over {@code telemetry_data}. */
public interface TelemetryAggregateProjection {

    Instant getBucket();

    Double getAvgPm25();

    Double getAvgCo2();

    Double getAvgTemperature();

    Double getAvgHumidity();

    Long getSampleCount();
}
