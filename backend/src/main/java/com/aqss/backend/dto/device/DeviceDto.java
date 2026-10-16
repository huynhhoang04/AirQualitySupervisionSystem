package com.aqss.backend.dto.device;

import java.time.Instant;

public record DeviceDto(
        String deviceId,
        String name,
        String locationName,
        String firmwareVersion,
        Boolean isActive,
        Instant lastSeenAt
) {
}
