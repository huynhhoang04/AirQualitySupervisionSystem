package com.aqss.backend.dto.forecast;

import java.time.Instant;

public record ForecastPointDto(
        Instant targetTime,
        Float pm25Q50,
        Float pm25Q10,
        Float pm25Q90
) {
}
