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

import java.time.Instant;
import java.util.UUID;

/** A forecast point and, once the target time passes, the observation that scores it. */
@Entity
@Table(name = "forecast_points", indexes = {
        @Index(name = "idx_point_target_status", columnList = "target_at, evaluation_status"),
        @Index(name = "idx_point_run", columnList = "run_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForecastPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "horizon_minutes", nullable = false)
    private Integer horizonMinutes;

    @Column(name = "target_at", nullable = false)
    private Instant targetAt;

    @Column(nullable = false)
    private Float pm25;

    @Column(name = "actual_pm25")
    private Float actualPm25;

    private Float error;

    /** PENDING | SCORED | UNSCORED_MISSING_TARGET | LATE_SUBMISSION */
    @Column(name = "evaluation_status", nullable = false, length = 30)
    private String evaluationStatus;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;
}
