package com.aqss.backend.dto.space;

/** io_contract §6.2 — ML#1 output with its GPR uncertainty band. */
public record ThresholdDto(
        Float baseThresholdPm25,
        Float uncertaintyLower,
        Float uncertaintyUpper,
        String modelVersion
) {
}
