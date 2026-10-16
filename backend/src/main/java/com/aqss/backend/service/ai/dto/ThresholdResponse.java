package com.aqss.backend.service.ai.dto;

/** io_contract §8 — ML#1 response. */
public record ThresholdResponse(
        String spaceId,
        Float baseThresholdPm25,
        Float uncertaintyLower,
        Float uncertaintyUpper,
        String modelVersion
) {
}
