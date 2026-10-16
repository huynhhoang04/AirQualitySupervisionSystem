package com.aqss.backend.dto.decision;

import com.aqss.backend.dto.space.DosageDto;

import java.time.Instant;

/** io_contract §7 — real-time decision stream pushed to {@code /topic/spaces/{id}/decision}. */
public record DecisionEventDto(
        String action,
        String mode,
        String trigger,
        Float threshold,
        DosageDto dosage,
        Instant timestamp
) {
}
