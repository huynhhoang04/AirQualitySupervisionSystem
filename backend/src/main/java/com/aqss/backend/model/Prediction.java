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

/** A forecast returned by Layer 3 (96 points, 24 h @ 15 min) — stored as raw JSON. */
@Entity
@Table(name = "predictions", indexes = @Index(name = "idx_predictions_device_time",
        columnList = "device_id, predicted_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "predicted_at", nullable = false)
    private Instant predictedAt;

    @Column(name = "model_version", length = 20)
    private String modelVersion;

    /** JSON array of {target_time, pm25_q10, pm25_q50, pm25_q90}. */
    @Column(columnDefinition = "text", nullable = false)
    private String points;
}
