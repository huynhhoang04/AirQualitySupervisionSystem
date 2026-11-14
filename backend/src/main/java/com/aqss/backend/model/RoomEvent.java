package com.aqss.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Operator context: a door opened, a session started, a note about what the room was doing. */
@Entity
@Table(name = "room_events", indexes =
        @Index(name = "idx_room_event_device_occurred", columnList = "device_id, occurred_at desc"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomEvent {

    @Id
    private UUID id;

    @Column(name = "device_id", length = 32)
    private String deviceId;

    @Column(name = "space_code", length = 20)
    private String spaceCode;

    /** When the operator says it happened. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** When it was recorded — a note written later has a different received time. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "experiment_id")
    private UUID experimentId;
}
