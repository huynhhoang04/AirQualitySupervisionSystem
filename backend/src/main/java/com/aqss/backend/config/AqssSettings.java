package com.aqss.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything the backend is configured with, in one place. */
@ConfigurationProperties(prefix = "aqss")
public record AqssSettings(
        Control control,
        Mqtt mqtt,
        Cors cors
) {
    /** Closed-loop tuning (io_contract §10). */
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
}
