package com.aqss.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MQTT broker connection and topic subscriptions (prefix {@code mqtt}). */
@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(
        String brokerUrl,
        String clientId,
        String username,
        String password,
        int qos,
        Topics topics
) {
    /** Wildcard subscriptions owned by the backend. */
    public record Topics(
            String telemetry,
            String status,
            String actuatorState
    ) {
    }
}
