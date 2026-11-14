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
 * A command the backend asked for. This is intent, not observation: {@code REPORT_MATCHED} only
 * means a later actuator report was consistent with the request.
 */
@Entity
@Table(name = "command_log", indexes = {
        @Index(name = "idx_command_device_requested", columnList = "device_id, requested_at desc"),
        @Index(name = "idx_command_status_requested", columnList = "status, requested_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommandLog {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(nullable = false, length = 40)
    private String command;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(nullable = false, length = 10)
    private String actor;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "publish_attempts", nullable = false)
    private Integer publishAttempts;

    /** REQUESTED | PUBLISHED | PUBLISH_FAILED | REPORT_MATCHED | UNCONFIRMED | SUPERSEDED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
