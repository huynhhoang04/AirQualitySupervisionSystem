package com.aqss.backend.service;

import com.aqss.backend.model.Space;
import com.aqss.backend.repo.SpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** Spaces + their PM2.5 threshold. Nothing else — the threshold is stored and sent to Layer 3. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpaceService {

    private final SpaceRepository spaces;
    private final AiClient ai;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<Space> findAll() {
        return spaces.findAll();
    }

    @Transactional(readOnly = true)
    public Space require(String code) {
        return spaces.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "space not found: " + code));
    }

    @Transactional
    public Space save(SpaceRequest request) {
        Space space = spaces.findById(request.code()).orElseGet(Space::new);
        space.setCode(request.code());
        space.setName(request.name() == null ? request.code() : request.name());
        space.setVolumeM3(request.volumeM3());
        space.setPurpose(request.purpose());
        space.setRegion(request.region());
        space.setVentilationEstimate(request.ventilationEstimate());
        if (request.thresholdPm25() != null) {
            space.setThresholdPm25(request.thresholdPm25());
            space.setThresholdSource("manual");
        } else if (space.getThresholdPm25() == null) {
            space.setThresholdPm25(35f);   // WHO 24 h interim target 3 — safe default
            space.setThresholdSource("manual");
        }
        space.setUpdatedAt(Instant.now());
        return spaces.save(space);
    }

    /** Ask Layer 3 (ML#1) for the threshold of this space; keeps the manual value when disabled. */
    @Transactional
    public Space recomputeThreshold(String code) {
        Space space = require(code);
        ai.threshold(new AiClient.ThresholdRequest(space.getCode(), space.getVolumeM3(), space.getPurpose(),
                        space.getRegion(), space.getVentilationEstimate(), 1))
                .ifPresent(result -> {
                    if (result.baseThresholdPm25() != null) {
                        space.setThresholdPm25(result.baseThresholdPm25());
                        space.setThresholdSource("ai");
                        space.setUpdatedAt(Instant.now());
                        audit.log("AI", "THRESHOLD", null, java.util.Map.of(
                                "space", space.getCode(),
                                "threshold_pm25", result.baseThresholdPm25(),
                                "model_version", String.valueOf(result.modelVersion())));
                    }
                });
        return spaces.save(space);
    }

    @Transactional
    public void delete(String code) {
        spaces.delete(require(code));
    }

    public record SpaceRequest(String code, String name, Float volumeM3, String purpose, String region,
                               String ventilationEstimate, Float thresholdPm25) {
    }
}
