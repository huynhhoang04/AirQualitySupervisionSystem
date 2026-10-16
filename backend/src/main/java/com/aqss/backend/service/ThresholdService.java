package com.aqss.backend.service;

import com.aqss.backend.entity.Space;
import com.aqss.backend.entity.SpaceThreshold;
import com.aqss.backend.repository.SpaceThresholdRepository;
import com.aqss.backend.service.ai.AiClient;
import com.aqss.backend.service.rules.ThresholdResult;
import com.aqss.backend.service.rules.ThresholdRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * ML#1 threshold, cached in {@code space_thresholds}. Calls the AI service when available and
 * falls back to {@link ThresholdRule}; recomputed whenever the space profile changes.
 */
@Service
@RequiredArgsConstructor
public class ThresholdService {

    private final SpaceThresholdRepository thresholdRepository;
    private final ThresholdRule thresholdRule;
    private final AiClient aiClient;

    @Transactional
    public SpaceThreshold getOrCompute(Space space) {
        return thresholdRepository.findById(space.getId()).orElseGet(() -> recompute(space));
    }

    @Transactional
    public SpaceThreshold recompute(Space space) {
        ThresholdResult result = aiClient.requestThreshold(space)
                .filter(r -> r.spaceId() != null && r.spaceId().equals(space.getCode())
                        && r.baseThresholdPm25() != null && r.uncertaintyLower() != null
                        && r.uncertaintyUpper() != null && r.baseThresholdPm25() >= 0
                        && r.baseThresholdPm25() <= 500 && r.uncertaintyLower() >= 0
                        && r.uncertaintyLower() <= r.baseThresholdPm25()
                        && r.baseThresholdPm25() <= r.uncertaintyUpper()
                        && r.uncertaintyUpper() <= 500 && r.modelVersion() != null)
                .map(r -> new ThresholdResult(r.baseThresholdPm25(), r.uncertaintyLower(),
                        r.uncertaintyUpper(), r.modelVersion()))
                .orElseGet(() -> thresholdRule.compute(space));

        SpaceThreshold entity = thresholdRepository.findById(space.getId()).orElseGet(SpaceThreshold::new);
        entity.setSpaceId(space.getId());
        entity.setBaseThresholdPm25((float) result.baseThresholdPm25());
        entity.setUncertaintyLower((float) result.uncertaintyLower());
        entity.setUncertaintyUpper((float) result.uncertaintyUpper());
        entity.setModelVersion(result.modelVersion());
        entity.setComputedAt(Instant.now());
        return thresholdRepository.save(entity);
    }
}
