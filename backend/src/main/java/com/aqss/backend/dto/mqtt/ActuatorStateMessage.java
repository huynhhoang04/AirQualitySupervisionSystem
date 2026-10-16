package com.aqss.backend.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Device → server actuator state payload (io_contract §2). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActuatorStateMessage(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("state") String state,
        @JsonProperty("level") Integer level,
        @JsonProperty("duty_pct") Integer dutyPct,
        @JsonProperty("mode") String mode,
        @JsonProperty("filter_runtime_h") Float filterRuntimeH
) {
}
