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

import java.time.Instant;

/**
 * The fundamental unit of AQSS V2. All thresholds, dosages and control decisions are scoped to
 * a Space, never to a raw device.
 */
@Entity
@Table(name = "spaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Device-facing identifier (e.g. {@code SP_201}) carried by telemetry and MQTT {@code set_space}. */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "area_m2")
    private Float areaM2;

    @Column(name = "ceiling_m")
    private Float ceilingM;

    @Column(name = "volume_m3", nullable = false)
    private Float volumeM3;

    @Column(nullable = false, length = 30)
    private String purpose;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(name = "ventilation_estimate", length = 20)
    private String ventilationEstimate;

    @Column(name = "created_at")
    private Instant createdAt;
}
