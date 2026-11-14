package com.aqss.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

/** A recorded observation session, used to describe what happened — not a causal proof. */
@Entity
@Table(name = "experiments", indexes =
        @Index(name = "idx_experiment_device_started", columnList = "device_id, started_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Experiment {

    @Id
    private UUID id;

    @Column(name = "device_id", length = 32)
    private String deviceId;

    @Column(name = "space_code", length = 20)
    private String spaceCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    @Column(name = "group_label", length = 30)
    private String groupLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String notes;
}
