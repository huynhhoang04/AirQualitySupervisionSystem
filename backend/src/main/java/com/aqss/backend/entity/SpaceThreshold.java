package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Cached ML#1 (threshold) + ML#2 (dosage) output for a space. */
@Entity
@Table(name = "space_thresholds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpaceThreshold {

    @Id
    @Column(name = "space_id")
    private Long spaceId;

    @Column(name = "base_threshold_pm25", nullable = false)
    private Float baseThresholdPm25;

    @Column(name = "uncertainty_lower")
    private Float uncertaintyLower;

    @Column(name = "uncertainty_upper")
    private Float uncertaintyUpper;

    @Column(name = "dosage_level")
    private Short dosageLevel;

    @Column(name = "dosage_duration_min")
    private Integer dosageDurationMin;

    @Column(name = "model_version", length = 20)
    private String modelVersion;

    @Column(name = "computed_at")
    private Instant computedAt;
}
