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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Immutable snapshot of a room profile at the moment a threshold was applied. */
@Entity
@Table(name = "space_profile_versions", indexes =
        @Index(name = "idx_profile_space_effective", columnList = "space_code, effective_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpaceProfileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_code", nullable = false, length = 20)
    private String spaceCode;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> profile;

    @Column(name = "threshold_pm25")
    private Float thresholdPm25;

    @Column(name = "threshold_source", length = 10)
    private String thresholdSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> factors;
}
