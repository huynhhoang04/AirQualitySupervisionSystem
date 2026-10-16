package com.aqss.backend.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Device → server status payload (io_contract §3). Published retained; LWT sets OFFLINE. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusMessage(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("status") String status,
        @JsonProperty("timestamp") Long timestamp
) {
    public boolean isOnline() {
        return "ONLINE".equalsIgnoreCase(status);
    }
}
