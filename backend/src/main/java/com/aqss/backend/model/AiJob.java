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

/** A queued train/evaluate/export request. Claimed atomically by one worker under a lease. */
@Entity
@Table(name = "ai_jobs", indexes = {
        @Index(name = "idx_ai_jobs_status_created", columnList = "status, created_at"),
        @Index(name = "idx_ai_jobs_device", columnList = "device_id, created_at desc")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiJob {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "device_id", length = 32)
    private String deviceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(nullable = false)
    private Integer progress;

    @Column(length = 30)
    private String phase;

    @Column(name = "data_cutoff")
    private Instant dataCutoff;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
