package com.aqss.backend.dto.telemetry;

import java.time.Instant;

public record HistoryPointDto(
        String deviceId,
        String spaceId,
        Instant recordedAt,
        Float pm25,
        Float co2,
        Float temperature,
        Float humidity
) {
}
