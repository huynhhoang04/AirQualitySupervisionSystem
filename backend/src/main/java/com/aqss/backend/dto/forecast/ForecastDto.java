package com.aqss.backend.dto.forecast;

import java.time.Instant;
import java.util.List;

/** io_contract §6.4 — 96-point quantile forecast. */
public record ForecastDto(
        String spaceId,
        Instant predictedAt,
        String modelVersion,
        List<ForecastPointDto> forecastSequence
) {
}
