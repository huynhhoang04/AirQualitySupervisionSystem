package com.aqss.backend.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Device → server telemetry payload (io_contract §1). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryMessage(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("space_id") String spaceId,
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("sensors") Sensors sensors,
        @JsonProperty("system") SystemInfo system
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sensors(
            @JsonProperty("pm25") Float pm25,
            @JsonProperty("co2") Float co2,
            @JsonProperty("temperature") Float temperature,
            @JsonProperty("humidity") Float humidity
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SystemInfo(
            @JsonProperty("wifi_rssi") Integer wifiRssi,
            @JsonProperty("uptime_sec") Long uptimeSec,
            @JsonProperty("free_heap") Long freeHeap,
            @JsonProperty("min_free_heap") Long minFreeHeap
    ) {
    }
}
