package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row = one simultaneous sensor reading. AQI is never stored (derived at read time).
 * No {@code @GeneratedValue} — see {@link TelemetryDataId}.
 */
@Entity
@Table(name = "telemetry_data")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelemetryData {

    @EmbeddedId
    private TelemetryDataId id;

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "space_id", length = 20)
    private String spaceId;

    @Column(nullable = false)
    private Float pm25;

    @Column(nullable = false)
    private Float co2;

    @Column(nullable = false)
    private Float temperature;

    @Column(nullable = false)
    private Float humidity;

    @Column(name = "wifi_rssi")
    private Short wifiRssi;

    @Column(name = "uptime_sec")
    private Integer uptimeSec;

    @Column(name = "free_heap")
    private Integer freeHeap;

    @Column(name = "min_free_heap")
    private Integer minFreeHeap;
}
