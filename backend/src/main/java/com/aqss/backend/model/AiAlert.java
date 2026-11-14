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

import java.time.Instant;
import java.util.UUID;

/** An early-warning episode derived from a forecast run, with its operator feedback. */
@Entity
@Table(name = "ai_alerts", indexes =
        @Index(name = "idx_alert_device_created", columnList = "device_id, created_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAlert {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "forecast_run_id")
    private UUID forecastRunId;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "threshold_snapshot")
    private Float thresholdSnapshot;

    @Column(name = "horizon_minutes")
    private Integer horizonMinutes;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false, length = 20)
    private String lifecycle;

    @Column(length = 30)
    private String feedback;

    @Column(name = "feedback_at")
    private Instant feedbackAt;
}
