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
 * Boots the full context against a real PostgreSQL 16 (Flyway V1→V4 must apply cleanly).
 * MQTT is disabled so no broker is required; the test is skipped when Docker is unavailable.
 */
@SpringBootTest(properties = "mqtt.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class BackendApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway applies every migration and the demo space/device are seeded")
    void should_ApplyMigrationsAndSeedDemoData_When_ContextStarts() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(4);

        String spaceCode = jdbcTemplate.queryForObject(
                "SELECT code FROM spaces WHERE code = 'SP_201'", String.class);
        assertThat(spaceCode).isEqualTo("SP_201");

        Integer partitions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_inherits i
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE p.relname = 'telemetry_data'
                """, Integer.class);
        assertThat(partitions).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("The monthly partition helper creates the following month on demand")
    void should_CreateNextMonthPartition_When_HelperIsInvoked() {
        String created = jdbcTemplate.queryForObject(
                "SELECT create_partition_for((date_trunc('month', NOW()) + INTERVAL '2 month')::date)",
                String.class);

        if (created != null) {
            assertThat(created).startsWith("telemetry_data_");
        }
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname = 'telemetry_data_' || to_char(date_trunc('month', NOW()) + INTERVAL '2 month', 'YYYY_MM')
                """, Integer.class);
        assertThat(exists).isEqualTo(1);
    }
}
