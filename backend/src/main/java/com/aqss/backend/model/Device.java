package com.aqss.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A node. Created automatically the first time it publishes telemetry. */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @Column(name = "device_id", length = 32)
    private String deviceId;

    @Column(length = 100)
    private String name;

    @Column(name = "space_code", length = 20)
    private String spaceCode;

    @Column(name = "fan_state", nullable = false, length = 3)
    private String fanState;

    @Column(name = "fan_level", nullable = false)
    private Short fanLevel;

    @Column(name = "fan_mode", nullable = false, length = 6)
    private String fanMode;

    @Column(name = "on_since")
    private Instant onSince;

    @Column(name = "last_command_at")
    private Instant lastCommandAt;

    @Column(name = "telemetry_interval_ms", nullable = false)
    private Integer telemetryIntervalMs;

    @Column(name = "dust_baseline_us", nullable = false)
    private Float dustBaselineUs;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
