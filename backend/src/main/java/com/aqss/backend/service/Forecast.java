package com.aqss.backend.service;

import com.aqss.backend.dto.forecast.ForecastPointDto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A cached DL forecast (96 quantile points). */
public record Forecast(
        Instant predictedAt,
        String modelVersion,
        List<ForecastPointDto> points
) {
    /**
     * Hours until the worst-case (q90) forecast first reaches the threshold, or {@code null} if it
     * never does within the 24-hour horizon.
     */
    public Double hoursUntilQ90Breach(double threshold) {
        Instant now = Instant.now();
        return points.stream()
                .filter(p -> p.pm25Q90() != null && p.targetTime() != null && p.pm25Q90() >= threshold)
                .mapToDouble(p -> Duration.between(now, p.targetTime()).toMinutes() / 60.0)
                .filter(hours -> hours >= 0)
                .min()
                .stream().boxed().findFirst().orElse(null);
    }
}
