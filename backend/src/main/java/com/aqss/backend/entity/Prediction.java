package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

/** Stored DL forecast: 96 quantile points (24 h @ 15 min) as JSONB. */
@Entity
@Table(name = "predictions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id")
    private Long spaceId;

    @Column(name = "predicted_at")
    private Instant predictedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forecast_data", nullable = false, columnDefinition = "jsonb")
    private String forecastData;

    @Column(name = "model_version", nullable = false, length = 20)
    private String modelVersion;
}
