package com.aqss.backend.dto.alert;

import java.time.Instant;

/** io_contract §6.6. */
public record AlertDto(
        Long id,
        Long spaceId,
        String deviceId,
        String alertType,
        String severity,
        String message,
        Instant triggeredAt,
        Instant resolvedAt
) {
}
