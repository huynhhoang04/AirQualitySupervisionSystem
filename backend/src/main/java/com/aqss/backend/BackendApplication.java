package com.aqss.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AQSS V2 — Layer 2 (the brain).
 *
 * <p>Closed-loop indoor air-quality control: MQTT telemetry ingestion, per-space threshold
 * inference, dosage prescription and anticipatory actuation of a PWM filter fan. The node only
 * measures, displays and actuates — every control decision is made here.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded: authentication is JWT/JPA-backed
 * ({@code AuthService}), so the default in-memory user is neither wanted nor used.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
