package com.aqss.backend.dto.telemetry;

import java.time.Instant;

public record AggregateRowDto(
        Instant bucket,
        Double avgPm25,
        Double avgCo2,
        Double avgTemperature,
        Double avgHumidity,
        Long sampleCount
) {
}
