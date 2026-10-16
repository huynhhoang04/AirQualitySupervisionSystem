package com.aqss.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AQSS domain configuration (prefix {@code aqss}). */
@ConfigurationProperties(prefix = "aqss")
public record AqssProperties(
        Ai ai,
        Control control,
        Telemetry telemetry
) {
    /** Layer 3 (AI) integration. Disabled in Phases 5–7 — rule-based stand-ins are used. */
    public record Ai(
            String baseUrl,
            int timeoutMs,
            boolean enabled
    ) {
    }

    /** Control loop safety and tuning constants. */
    public record Control(
            long cycleMs,
            String forecastCron,
            double hysteresisRatio,
            int minOnMinutes,
            int minOffMinutes,
            int maxOnMinutes,
            int failSafeMinutes,
            double defaultFilterCadr,
            double penetrationFactor,
            double outdoorPm25Estimate
    ) {
    }

    /** Telemetry ingestion tuning. */
    public record Telemetry(
            long batchFlushIntervalMs,
            int queueCapacity,
            int rollingWindowMinutes
    ) {
    }
}
