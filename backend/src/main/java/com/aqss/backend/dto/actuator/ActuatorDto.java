package com.aqss.backend.dto.actuator;

import java.time.Instant;

public record ActuatorDto(
        String deviceId,
        Long spaceId,
        String state,
        Short level,
        Short dutyPct,
        String mode,
        Float filterRuntimeH,
        Instant since
) {
}
