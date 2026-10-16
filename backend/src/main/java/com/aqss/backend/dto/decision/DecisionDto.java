package com.aqss.backend.dto.decision;

import java.time.Instant;

/** io_contract §6.3 — one control-decision audit row. */
public record DecisionDto(
        Long id,
        Instant decidedAt,
        String action,
        String mode,
        String triggerReason,
        Float pm25AtDecision,
        Float thresholdUsed,
        Short dosageLevel,
        Integer dosageDurationMin
) {
}
