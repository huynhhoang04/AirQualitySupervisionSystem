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

/** Metadata for a trained artifact. The artifact itself lives on the worker's disk. */
@Entity
@Table(name = "ai_models", indexes =
        @Index(name = "idx_model_device_created", columnList = "device_id, created_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModel {

    @Id
    private UUID id;

    @Column(name = "device_id", length = 32)
    private String deviceId;

    @Column(nullable = false, length = 80)
    private String version;

    @Column(length = 40)
    private String algorithm;

    @Column(name = "feature_version", length = 20)
    private String featureVersion;

    @Column(name = "dataset_snapshot_id")
    private UUID datasetSnapshotId;

    @Column(name = "dataset_hash", length = 64)
    private String datasetHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "train_cutoff")
    private Instant trainCutoff;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @Column(name = "artifact_hash", length = 64)
    private String artifactHash;

    @Column(name = "artifact_path", length = 255)
    private String artifactPath;

    /** EXPERIMENTAL | PRODUCTION | REJECTED | ARTIFACT_MISSING */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "promotion_reason", columnDefinition = "text")
    private String promotionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
