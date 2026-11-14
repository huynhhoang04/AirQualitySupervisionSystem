package com.aqss.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything the backend is configured with, in one place. */
@ConfigurationProperties(prefix = "aqss")
public record AqssSettings(
        Control control,
        Mqtt mqtt,
        Cors cors,
        Ai ai
) {
    /** Closed-loop tuning (io_contract §9). */
    public record Control(long cycleMs,
                          double hysteresisRatio,
                          int minOnMinutes,
                          int minOffMinutes,
                          int maxOnMinutes,
                          int staleReadingMinutes,
                          int leaseRenewMinutes) {
    }

    public record Mqtt(String brokerUrl, String clientId, String username, String password, int qos) {
    }

    public record Cors(String origin) {
    }

    /**
     * AI is optional. Turning it off must not affect telemetry, control or pairing.
     * The worker token guards {@code /internal/**}; blank means local development mode.
     */
    public record Ai(boolean enabled, String workerToken) {
    }
}
