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

import java.time.Instant;

/** One sensor reading. */
@Entity
@Table(name = "telemetry", indexes = @Index(name = "idx_telemetry_device_time",
        columnList = "device_id, recorded_at desc"))
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

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    private Float pm25;

    private Float co2;

    private Float temperature;

    private Float humidity;

    @Column(name = "wifi_rssi")
    private Short wifiRssi;

    /** The dust sensor reached its rail; {@code pm25} is a floor, not a measurement. */
    @Column(name = "pm25_saturated", nullable = false)
    private boolean pm25Saturated;
}
