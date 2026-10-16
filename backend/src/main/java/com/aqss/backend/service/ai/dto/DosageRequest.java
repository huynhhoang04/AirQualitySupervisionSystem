package com.aqss.backend.service.ai.dto;

/** io_contract §9 — ML#2 request. */
public record DosageRequest(
        String spaceId,
        Float volumeM3,
        String purpose,
        Float currentPm25,
        Float baseThreshold,
        Float outdoorPm25Estimate,
        Float filterCadr
) {
}
