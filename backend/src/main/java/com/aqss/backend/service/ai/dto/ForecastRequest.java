package com.aqss.backend.service.ai.dto;

import java.time.Instant;
import java.util.List;

/** io_contract §10 — DL forecast request: exactly 96 points (24 h @ 15 min). */
public record ForecastRequest(
        String spaceId,
        List<SeriesPoint> series
) {
    public record SeriesPoint(Instant timestamp, Float pm25) {
    }
}
