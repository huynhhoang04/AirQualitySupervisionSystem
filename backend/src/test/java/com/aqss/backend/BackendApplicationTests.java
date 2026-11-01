package com.aqss.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full context against a real PostgreSQL 16 (Flyway V1 must apply cleanly).
 * MQTT is disabled so no broker is required; the test is skipped when Docker is unavailable.
 */
@SpringBootTest(properties = "aqss.mqtt.broker-url=tcp://localhost:1")
@Testcontainers(disabledWithoutDocker = true)
class BackendApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway applies the schema and the demo space/device are seeded")
    void should_ApplyMigrationsAndSeedDemoData_When_ContextStarts() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT threshold_pm25 FROM spaces WHERE code = 'SP_201'", Float.class)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT space_code FROM devices WHERE device_id = 'AQSS_ESP32S3_01'", String.class))
                .isEqualTo("SP_201");
    }
}
