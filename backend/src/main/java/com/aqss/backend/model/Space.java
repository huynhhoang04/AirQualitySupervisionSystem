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

import java.time.Instant;

/** A monitored room. Its PM2.5 threshold comes from the room profile or a manual override. */
@Entity
@Table(name = "spaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Space {

    @Id
    @Column(length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "volume_m3")
    private Float volumeM3;

    @Column(length = 30)
    private String purpose;

    @Column(length = 50)
    private String region;

    @Column(name = "ventilation_estimate", length = 20)
    private String ventilationEstimate;

    /** Operator override; null means "derive it from purpose". */
    @Column(name = "occupancy_hours")
    private Integer occupancyHours;

    /** Bumped whenever the profile changes; stamped on forecasts so they can be replayed. */
    @Builder.Default
    @Column(name = "profile_version", nullable = false)
    private Integer profileVersion = 1;

    @Column(name = "threshold_pm25", nullable = false)
    private Float thresholdPm25;

    @Column(name = "threshold_source", nullable = false, length = 10)
    private String thresholdSource;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
