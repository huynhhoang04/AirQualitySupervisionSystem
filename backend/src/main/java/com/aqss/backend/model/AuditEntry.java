package com.aqss.backend.model;

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

/** One log line for every action the backend takes or receives. */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** USER | BACKEND | DEVICE | AI */
    @Column(nullable = false, length = 10)
    private String actor;

    /** ACTIVATE | DEACTIVATE | MANUAL | COMMAND | CONFIG | THRESHOLD | STATUS ... */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "device_id", length = 32)
    private String deviceId;

    @Column(columnDefinition = "text")
    private String detail;
}
