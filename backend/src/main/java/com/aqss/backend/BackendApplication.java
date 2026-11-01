package com.aqss.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AQSS V2 — Layer 2 (the brain).
 *
 * <p>Collect telemetry over MQTT, serve it to the dashboard, drive the filter fan, and call
 * Layer 3 (threshold / dosage / forecast). No auth, no partitioned telemetry.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
