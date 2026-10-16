package com.aqss.backend.dto.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDeviceRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "AQSS_ESP32S3_\\w+") String deviceId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 150) String locationName,
        Double latitude,
        Double longitude,
        String firmwareVersion
) {
}
