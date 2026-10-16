package com.aqss.backend.dto.actuator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Manual control request — {@code POST /api/v1/spaces/{id}/actuator}. */
public record ManualControlRequest(
        @NotNull @Pattern(regexp = "AUTO|MANUAL") String mode,
        @NotNull @Pattern(regexp = "ON|OFF") String state,
        @Min(0) @Max(3) Integer level
) {
}
