package com.aqss.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** One actuator report from the node. Append-only: this is what the device said, not what we asked. */
@Entity
@Table(name = "actuator_history", indexes =
        @Index(name = "idx_actuator_device_received", columnList = "device_id, received_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActuatorHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(length = 3)
    private String state;

    private Short level;

    @Column(name = "duty_pct")
    private Short dutyPct;

    @Column(length = 6)
    private String mode;

    @Column(name = "filter_runtime_s")
    private Integer filterRuntimeS;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> quality = Map.of();
}
