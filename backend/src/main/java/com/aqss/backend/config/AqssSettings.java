package com.aqss.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything the backend is configured with, in one place. */
@ConfigurationProperties(prefix = "aqss")
public record AqssSettings(
        Ai ai,
        Control control,
        Mqtt mqtt,
        Cors cors
) {
    /** Layer 3. When {@code enabled} is false the backend keeps working with manual values. */
    public record Ai(String baseUrl, int timeoutMs, boolean enabled) {
    }

    /** Closed-loop tuning (io_contract §10). */
    public record Control(long cycleMs,
                          double hysteresisRatio,
                          int minOnMinutes,
                          int minOffMinutes,
                          int maxOnMinutes,
                          int staleReadingMinutes,
                          int forecastHorizonMinutes,
                          int forecastMaxAgeMinutes,
                          int leaseRenewMinutes) {
    }

    public record Mqtt(String brokerUrl, String clientId, String username, String password, int qos) {
    }

    public record Cors(String origin) {
    }
}
