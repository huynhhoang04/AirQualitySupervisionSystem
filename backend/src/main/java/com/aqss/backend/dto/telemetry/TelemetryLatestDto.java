package com.aqss.backend.dto.telemetry;

import java.time.Instant;

/** io_contract §6.1 — latest reading with derived, display-only AQI. */
public record TelemetryLatestDto(
        String deviceId,
        String spaceId,
        Instant recordedAt,
        Float pm25,
        Float co2,
        Float temperature,
        Float humidity,
        Integer aqiIndex,
        String aqiLevel,
        Boolean co2Alert,
        Short wifiRssi
) {
}
