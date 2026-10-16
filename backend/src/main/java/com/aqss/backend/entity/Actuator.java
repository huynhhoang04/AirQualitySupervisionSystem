package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Live actuator (filter fan) state, tracked from {@code actuator/state} and command intent. */
@Entity
@Table(name = "actuators")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Actuator {

    @Id
    @Column(name = "device_id", length = 32)
    private String deviceId;

    @Column(name = "space_id")
    private Long spaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", length = 10)
    private ActuatorState currentState;

    @Column(name = "current_level")
    private Short currentLevel;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ActuatorMode mode;

    @Column(name = "duty_pct")
    private Short dutyPct;

    @Column(name = "filter_runtime_h")
    private Float filterRuntimeH;

    @Column(name = "last_command_at")
    private Instant lastCommandAt;

    @Column(name = "state_since")
    private Instant stateSince;
}
