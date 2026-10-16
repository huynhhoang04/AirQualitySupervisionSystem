package com.aqss.backend.service.ai.dto;

/** io_contract §9 — ML#2 response. */
public record DosageResponse(
        String spaceId,
        Short filterLevel,
        Integer runDurationMinutes,
        String modelVersion
) {
}
