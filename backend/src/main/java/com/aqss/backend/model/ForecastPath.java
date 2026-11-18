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
import java.util.List;
import java.util.UUID;

/**
 * The full forecast curve for one run (io_contract §14).
 *
 * <p>One row per run instead of one row per value: at a 5 s cadence that is the difference between
 * a table that can be queried and a table that cannot. Index {@code k} is the value at
 * {@code anchorAt + (k+1) × stepSeconds}.
 */
@Entity
@Table(name = "forecast_paths")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForecastPath {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "anchor_at", nullable = false)
    private Instant anchorAt;

    @Column(name = "anchor_pm25", nullable = false)
    private Float anchorPm25;

    @Column(name = "step_seconds", nullable = false)
    private Integer stepSeconds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "values", nullable = false, columnDefinition = "real[]")
    private List<Float> values;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "q10", columnDefinition = "real[]")
    private List<Float> q10;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "q90", columnDefinition = "real[]")
    private List<Float> q90;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
