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

/** One forecast issued before its targets, so it can be scored prospectively. */
@Entity
@Table(name = "forecast_runs", indexes =
        @Index(name = "idx_forecast_device_issued", columnList = "device_id, issued_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForecastRun {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "model_id")
    private UUID modelId;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "input_cutoff", nullable = false)
    private Instant inputCutoff;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** Server clock. A run uploaded after a target is late, whatever the worker claims. */
    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "profile_version")
    private Integer profileVersion;

    @Column(name = "threshold_pm25")
    private Float thresholdPm25;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_quality", columnDefinition = "jsonb")
    private Map<String, Object> dataQuality;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(nullable = false, length = 20)
    private String status;
}
