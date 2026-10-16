package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Audit log of every control-loop decision (full traceability). */
@Entity
@Table(name = "control_decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ControlDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id")
    private Long spaceId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DecisionAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DecisionMode mode;

    @Column(name = "trigger_reason", length = 40)
    private String triggerReason;

    @Column(name = "pm25_at_decision")
    private Float pm25AtDecision;

    @Column(name = "threshold_used")
    private Float thresholdUsed;

    @Column(name = "dosage_level")
    private Short dosageLevel;

    @Column(name = "dosage_duration_min")
    private Integer dosageDurationMin;
}
