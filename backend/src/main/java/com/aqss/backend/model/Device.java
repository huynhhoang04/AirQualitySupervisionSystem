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

    /** Shown on the node's OLED; the operator types it into the dashboard to pair. */
    @Column(name = "pairing_code", length = 8)
    private String pairingCode;

    /** Null until an operator links the node to a room — unpaired telemetry is stored but hidden. */
    @Column(name = "paired_at")
    private Instant pairedAt;

    @Column(name = "fan_state", nullable = false, length = 3)
    private String fanState;

    @Column(name = "fan_level", nullable = false)
    private Short fanLevel;

    @Column(name = "fan_mode", nullable = false, length = 6)
    private String fanMode;

    @Column(name = "on_since")
    private Instant onSince;

    /** Start of the current OFF period — the min-OFF safety window counts from here. */
    @Column(name = "off_since")
    private Instant offSince;

    @Column(name = "last_command_at")
    private Instant lastCommandAt;

    @Column(name = "telemetry_interval_ms", nullable = false)
    private Integer telemetryIntervalMs;

    @Column(name = "dust_baseline_us", nullable = false)
    private Float dustBaselineUs;

    /** Liveness follows the receive clock, not a possibly replayed device timestamp. */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_telemetry_received_at")
    private Instant lastTelemetryReceivedAt;

    /** When the last actuator report was accepted; distinguishes "reported" from "requested". */
    @Column(name = "last_actuator_received_at")
    private Instant lastActuatorReceivedAt;

    /** Fan hours the node has counted. Published on every actuator-state message. */
    @Column(name = "filter_runtime_s", nullable = false)
    private Integer filterRuntimeS;

    /** When the operator last said "new filter fitted"; the runtime counts from here. */
    @Column(name = "filter_replaced_at")
    private Instant filterReplacedAt;

    /** Why the loop last acted: threshold_breach, hysteresis_off, max_on, manual. */
    @Column(name = "last_reason", length = 30)
    private String lastReason;

    @Column(name = "last_decision_at")
    private Instant lastDecisionAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
