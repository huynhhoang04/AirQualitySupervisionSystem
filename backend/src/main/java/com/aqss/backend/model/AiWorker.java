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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** A local AI worker that polls for jobs and uploads forecasts. */
@Entity
@Table(name = "ai_workers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiWorker {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> capabilities;

    @Column(name = "model_artifact", length = 200)
    private String modelArtifact;

    @Column(nullable = false, length = 20)
    private String status;
}
