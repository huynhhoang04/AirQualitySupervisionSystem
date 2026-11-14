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

/** One sensor reading, including the provenance needed to learn from it later. */
@Entity
@Table(name = "telemetry", indexes = {
        @Index(name = "idx_telemetry_device_time", columnList = "device_id, recorded_at desc"),
        @Index(name = "idx_telemetry_device_recorded_id", columnList = "device_id, recorded_at, id"),
        @Index(name = "idx_telemetry_received", columnList = "received_at, id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Telemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    /** Device timestamp when valid, otherwise the server receive time. Drives charts and control. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** When the backend actually accepted the message. Null for rows written before V5. */
    @Column(name = "received_at")
    private Instant receivedAt;

    /** device | server_assigned | legacy */
    @Column(name = "time_quality", length = 20)
    private String timeQuality;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_flags", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> qualityFlags = Map.of();

    private Float pm25;

    private Float co2;

    private Float temperature;

    private Float humidity;

    @Column(name = "wifi_rssi")
    private Short wifiRssi;

    /** The dust sensor reached its rail; {@code pm25} is a floor, not a measurement. */
    @Column(name = "pm25_saturated", nullable = false)
    private boolean pm25Saturated;

    @Column(name = "dust_fault")
    private Boolean dustFault;

    @Column(name = "uptime_sec")
    private Long uptimeSec;

    @Column(name = "free_heap")
    private Long freeHeap;

    @Column(name = "min_free_heap")
    private Long minFreeHeap;

    /** SHA-256 of the canonical payload; identical messages are stored once. */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;
}
