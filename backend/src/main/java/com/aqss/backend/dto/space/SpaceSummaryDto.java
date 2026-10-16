package com.aqss.backend.dto.space;

/** Lightweight row for the space list. */
public record SpaceSummaryDto(
        Long id,
        String code,
        String name,
        Float volumeM3,
        String purpose,
        String region,
        String ventilationEstimate,
        Float thresholdPm25
) {
}
