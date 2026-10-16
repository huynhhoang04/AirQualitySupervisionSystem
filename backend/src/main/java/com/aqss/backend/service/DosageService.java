package com.aqss.backend.service;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.entity.Space;
import com.aqss.backend.service.ai.AiClient;
import com.aqss.backend.service.rules.DosageResult;
import com.aqss.backend.service.rules.DosageRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ML#2 dosage: fan level + run duration for a room at a given pollution level. Calls the AI
 * service when available and falls back to {@link DosageRule} (clean-up-time model).
 */
@Service
@RequiredArgsConstructor
public class DosageService {

    private final AiClient aiClient;
    private final DosageRule dosageRule;
    private final AqssProperties properties;

    public DosageResult compute(Space space, double currentPm25, double baseThreshold) {
        double cadr = properties.control().defaultFilterCadr();
        return aiClient.requestDosage(space, currentPm25, baseThreshold, cadr)
                .filter(r -> space.getCode().equals(r.spaceId()) && r.filterLevel() != null
                        && r.filterLevel() >= 0 && r.filterLevel() <= 3
                        && r.runDurationMinutes() != null && r.runDurationMinutes() >= 1
                        && r.runDurationMinutes() <= properties.control().maxOnMinutes()
                        && r.modelVersion() != null)
                .map(r -> new DosageResult(r.filterLevel(), r.runDurationMinutes(), r.modelVersion()))
                .orElseGet(() -> dosageRule.compute(space, currentPm25, baseThreshold, cadr,
                        properties.control().outdoorPm25Estimate(),
                        properties.control().penetrationFactor()));
    }
}
