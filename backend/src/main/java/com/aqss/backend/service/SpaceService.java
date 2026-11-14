package com.aqss.backend.service;

import com.aqss.backend.model.Space;
import com.aqss.backend.model.SpaceProfileVersion;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceProfileVersionRepository;
import com.aqss.backend.repo.SpaceRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spaces and their PM2.5 threshold: a stored policy value with its room-profile provenance. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpaceService {

    /** io_contract §9: a space threshold is a PM2.5 value, so it lives inside the same window. */
    private static final float THRESHOLD_MIN = 1f;
    private static final float THRESHOLD_MAX = 500f;

    private final SpaceRepository spaces;
    private final SpaceProfileVersionRepository profiles;
    private final TelemetryRepository telemetry;
    private final DeviceRepository devices;
    private final ThresholdService thresholds;
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
        if (request.occupancyHours() != null) {
            space.setOccupancyHours((int) ThresholdService.clampHours(request.occupancyHours()));
        }
        if (request.thresholdPm25() != null) {
            space.setThresholdPm25(validateThreshold(request.thresholdPm25()));
            space.setThresholdSource("manual");
        } else if (space.getThresholdPm25() == null) {
            space.setThresholdPm25(35f);   // WHO 24 h interim target 3 — safe default
            space.setThresholdSource("manual");
        }
        space.setUpdatedAt(Instant.now());

        ThresholdService.Result factors = thresholds.compute(space, currentCo2(space));
        recordProfileVersion(space, factors);
        return spaces.save(space);
    }

    /**
     * What the room profile implies, without writing anything. The dashboard shows this so the
     * operator can read the reasoning before accepting the number.
     */
    @Transactional(readOnly = true)
    public ThresholdPreview previewThreshold(String code) {
        Space space = require(code);
        return new ThresholdPreview(space, thresholds.compute(space, currentCo2(space)));
    }

    /**
     * Store the profile-derived threshold. Unlike {@link #save}, this marks the threshold as
     * computed rather than manual, so the dashboard can say where the number came from.
     */
    @Transactional
    public ThresholdPreview applyComputedThreshold(String code) {
        Space space = require(code);
        ThresholdService.Result result = thresholds.compute(space, currentCo2(space));
        space.setThresholdPm25(validateThreshold((float) result.thresholdPm25()));
        space.setThresholdSource("profile");
        space.setUpdatedAt(Instant.now());
        recordProfileVersion(space, result);
        spaces.save(space);
        audit.log("BACKEND", "THRESHOLD", null, java.util.Map.of(
                "space", space.getCode(),
                "threshold_pm25", result.thresholdPm25(),
                "co2_factor", result.co2Factor(),
                "ventilation_factor", result.ventilationFactor(),
                "occupancy_hours", result.occupancyHours()));
        return new ThresholdPreview(space, result);
    }

    /**
     * Records the profile that produced a threshold. A forecast can then be replayed against the
     * exact volume/purpose/ventilation/occupancy it was issued under, not today's values.
     */
    private void recordProfileVersion(Space space, ThresholdService.Result factors) {
        int next = profiles.findFirstBySpaceCodeOrderByVersionDesc(space.getCode())
                .map(previous -> previous.getVersion() + 1)
                .orElse(1);
        space.setProfileVersion(next);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("code", space.getCode());
        profile.put("name", space.getName());
        profile.put("volume_m3", space.getVolumeM3());
        profile.put("purpose", space.getPurpose());
        profile.put("region", space.getRegion());
        profile.put("ventilation_estimate", space.getVentilationEstimate());
        profile.put("occupancy_hours", space.getOccupancyHours());

        Map<String, Object> factorMap = new LinkedHashMap<>();
        factorMap.put("daily_dose_target", factors.dailyDoseTarget());
        factorMap.put("volume_factor", factors.volumeFactor());
        factorMap.put("ventilation_factor", factors.ventilationFactor());
        factorMap.put("co2_factor", factors.co2Factor());
        factorMap.put("occupancy_hours", factors.occupancyHours());
        factorMap.put("co2_ppm", factors.co2Ppm());

        profiles.save(SpaceProfileVersion.builder()
                .spaceCode(space.getCode())
                .version(next)
                .effectiveAt(Instant.now())
                .profile(profile)
                .thresholdPm25(space.getThresholdPm25())
                .thresholdSource(space.getThresholdSource())
                .factors(factorMap)
                .build());
    }

    /**
     * The room's most recent CO₂, which is what makes the threshold react to how sealed the room is
     * right now. Null when the room has no device or no reading yet.
     */
    private Double currentCo2(Space space) {
        return spaces.findById(space.getCode())
                .map(Space::getCode)
                .flatMap(code -> telemetry.findFirstByDeviceIdOrderByRecordedAtDesc(deviceOf(code))
                        .map(reading -> reading.getCo2() == null ? null : reading.getCo2().doubleValue()))
                .orElse(null);
    }

    private String deviceOf(String spaceCode) {
        return devices.findBySpaceCode(spaceCode).stream()
                .findFirst()
                .map(com.aqss.backend.model.Device::getDeviceId)
                .orElse("");
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
                               String ventilationEstimate, Integer occupancyHours, Float thresholdPm25) {
    }

    /** The space plus the reasoning that produced its suggested threshold. */
    public record ThresholdPreview(Space space, ThresholdService.Result computed) {
    }
}
