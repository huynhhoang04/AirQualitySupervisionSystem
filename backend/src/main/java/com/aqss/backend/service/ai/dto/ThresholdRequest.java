package com.aqss.backend.service.ai.dto;

/** io_contract §8 — ML#1 request. */
public record ThresholdRequest(
        String spaceId,
        Float volumeM3,
        String purpose,
        String region,
        String ventilationEstimate,
        Integer occupancyTypical
) {
}
