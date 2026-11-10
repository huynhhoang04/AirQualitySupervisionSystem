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

    /** io_contract §9: a space threshold is a PM2.5 value, so it lives inside the same window. */
    private static final float THRESHOLD_MIN = 1f;
    private static final float THRESHOLD_MAX = 500f;

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

    /**
     * Patch, not replace: the dashboard's threshold card sends only {@code threshold_pm25}, and a
     * full replace would erase the room profile the ML#1 threshold is derived from.
     */
    @Transactional
    public Space save(SpaceRequest request) {
        Space space = spaces.findById(request.code()).orElseGet(() -> {
            Space created = new Space();
            created.setCode(request.code());
            created.setName(request.code());
            return created;
        });

        if (request.name() != null && !request.name().isBlank()) {
            space.setName(request.name());
        }
        if (request.volumeM3() != null) {
            space.setVolumeM3(request.volumeM3());
        }
        if (request.purpose() != null) {
            space.setPurpose(request.purpose());
        }
        if (request.region() != null) {
            space.setRegion(request.region());
        }
        if (request.ventilationEstimate() != null) {
            space.setVentilationEstimate(request.ventilationEstimate());
        }
        if (request.thresholdPm25() != null) {
            space.setThresholdPm25(validateThreshold(request.thresholdPm25()));
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
                        space.setThresholdPm25(validateThreshold(result.baseThresholdPm25()));
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

    private static float validateThreshold(float value) {
        if (value < THRESHOLD_MIN || value > THRESHOLD_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "threshold_pm25 must be between " + THRESHOLD_MIN + " and " + THRESHOLD_MAX);
        }
        return value;
    }

    public record SpaceRequest(String code, String name, Float volumeM3, String purpose, String region,
                               String ventilationEstimate, Float thresholdPm25) {
    }
}
