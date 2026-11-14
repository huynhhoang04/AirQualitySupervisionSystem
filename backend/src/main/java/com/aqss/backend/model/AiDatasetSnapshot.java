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

/**
 * Immutable membership of one dataset export. The ordered ID manifest is what makes paginated reads
 * stable while new telemetry keeps arriving.
 */
@Entity
@Table(name = "ai_dataset_snapshots", indexes =
        @Index(name = "idx_snapshot_device_created", columnList = "device_id, created_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiDatasetSnapshot {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "from_at", nullable = false)
    private Instant fromAt;

    @Column(name = "to_at", nullable = false)
    private Instant toAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> manifest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_manifest", columnDefinition = "jsonb")
    private Map<String, Object> contextManifest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> counts;

    @Column(name = "snapshot_hash", length = 64)
    private String snapshotHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
