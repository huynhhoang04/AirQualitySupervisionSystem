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

/** Integrated ESP32-S3 node registry. */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @Column(name = "device_id", length = 32, nullable = false)
    private String deviceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "location_name", length = 150)
    private String locationName;

    private Double latitude;

    private Double longitude;

    @Column(name = "api_key_hash", nullable = false, length = 64)
    private String apiKeyHash;

    @Column(name = "firmware_version", length = 20)
    private String firmwareVersion;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
