package com.aqss.backend.dto.space;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AssignDeviceRequest(
        @NotBlank String deviceId,
        @Pattern(regexp = "SENSOR|ACTUATOR|COMBINED") String role
) {
}
