package com.aqss.backend.dto.space;

import java.util.List;

/** io_contract §6.2 — full space detail. */
public record SpaceDto(
        Long id,
        String code,
        String name,
        Float areaM2,
        Float ceilingM,
        Float volumeM3,
        String purpose,
        String region,
        String ventilationEstimate,
        List<String> devices,
        List<String> actuators,
        ThresholdDto threshold,
        DosageDto dosage
) {
}
