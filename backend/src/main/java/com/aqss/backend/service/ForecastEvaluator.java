package com.aqss.backend.service;

import com.aqss.backend.model.ForecastPoint;
import com.aqss.backend.model.ForecastRun;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.repo.ForecastPointRepository;
import com.aqss.backend.repo.ForecastRunRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Scores issued forecasts against what the device later measured (io_contract §13).
 *
 * <p>A point is scored only when the backend accepted its run before the target — a run uploaded
 * after the fact is stored for analysis but never counted as a forecast. A target that passes
 * without a usable sample becomes {@code UNSCORED_MISSING_TARGET}; it is not assigned error 0,
 * because a missing measurement says nothing about the model.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForecastEvaluator {

    /** Targets are matched to the nearest sample inside this window; jitter is normal, gaps are not. */
    static final Duration MATCH_TOLERANCE = Duration.ofSeconds(3);

    /** Data may still arrive this long after a target before the point is declared missing. */
    static final Duration LATE_DATA_GRACE = Duration.ofMinutes(10);

    private static final int BATCH = 500;

    private final ForecastPointRepository points;
    private final ForecastRunRepository runs;
    private final TelemetryRepository telemetry;
    private final LiveEventService live;

    /** Due targets are scored within one poll interval; there is no per-point timer. */
    @Scheduled(fixedDelayString = "${aqss.ai.evaluation-interval-ms:15000}")
    public void tick() {
        Instant now = Instant.now();
        evaluateDue(now);
        expireMissing(now.minus(LATE_DATA_GRACE));
    }

    @Transactional
    public int evaluateDue(Instant now) {
        return evaluateDueInternal(now);
    }

    @Transactional
    public int expireMissing(Instant before) {
        return expireMissingInternal(before);
    }

    private int evaluateDueInternal(Instant now) {
        List<ForecastPoint> due = points
                .findByEvaluationStatusAndTargetAtBeforeOrderByTargetAtAsc(
                        "PENDING", now, PageRequest.of(0, BATCH));
        if (due.isEmpty()) {
            return 0;
        }
        Map<UUID, ForecastRun> runsById = loadRuns(due);
        List<ForecastPoint> scored = new ArrayList<>();
        for (ForecastPoint point : due) {
            ForecastRun run = runsById.get(point.getRunId());
            // Without its run there is no issue time, so there is no prospective claim to check.
            if (run == null || !run.getAcceptedAt().isBefore(point.getTargetAt())) {
                continue;
            }
            Optional<Telemetry> match = nearestSample(run.getDeviceId(), point.getTargetAt());
            if (match.isEmpty()) {
                continue;   // may still arrive inside the grace window
            }
            Telemetry sample = match.get();
            point.setActualPm25(sample.getPm25());
            point.setError(sample.getPm25() - point.getPm25());
            point.setEvaluationStatus("SCORED");
            point.setEvaluatedAt(now);
            scored.add(point);
        }
        if (scored.isEmpty()) {
            return 0;
        }
        points.saveAll(scored);
        publishMetrics(scored, runsById);
        log.debug("Scored {} forecast points", scored.size());
        return scored.size();
    }

    private int expireMissingInternal(Instant before) {
        List<ForecastPoint> abandoned = points
                .findByEvaluationStatusAndTargetAtBeforeOrderByTargetAtAsc(
                        "PENDING", before, PageRequest.of(0, BATCH));
        if (abandoned.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        for (ForecastPoint point : abandoned) {
            point.setEvaluationStatus("UNSCORED_MISSING_TARGET");
            point.setEvaluatedAt(now);
        }
        points.saveAll(abandoned);
        publishMetrics(abandoned, loadRuns(abandoned));
        return abandoned.size();
    }

    /**
     * Nearest *usable* sample at or before the target inside the tolerance window. Earlier wins a
     * tie, so a point is never scored from two samples and a gap is never bridged by interpolation.
     *
     * <p>A saturated or dust-fault reading is not a measurement of the room: it is a floor or a
     * sensor that is not working. Scoring against it would credit or blame the model for a value
     * the sensor never produced, so it is skipped like a missing sample.
     */
    private Optional<Telemetry> nearestSample(String deviceId, Instant target) {
        List<Telemetry> candidates = telemetry
                .findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(
                        deviceId, target.minus(MATCH_TOLERANCE), target.plus(MATCH_TOLERANCE));
        return candidates.stream()
                .filter(ForecastEvaluator::isUsableReading)
                .filter(sample -> Duration.between(sample.getRecordedAt(), target).abs()
                        .compareTo(MATCH_TOLERANCE) <= 0)
                .min((a, b) -> {
                    Duration da = Duration.between(a.getRecordedAt(), target).abs();
                    Duration db = Duration.between(b.getRecordedAt(), target).abs();
                    int byDistance = da.compareTo(db);
                    return byDistance != 0 ? byDistance : a.getRecordedAt().compareTo(b.getRecordedAt());
                });
    }

    /** Same acceptance rule the ingestion path and the AI pipeline use (io_contract §8, §11.1). */
    static boolean isUsableReading(Telemetry sample) {
        if (sample.getPm25() == null || sample.isPm25Saturated()) {
            return false;
        }
        return !Boolean.TRUE.equals(sample.getDustFault());
    }

    private Map<UUID, ForecastRun> loadRuns(List<ForecastPoint> batch) {
        Map<UUID, ForecastRun> byId = new HashMap<>();
        for (ForecastRun run : runs.findAllById(batch.stream().map(ForecastPoint::getRunId).distinct().toList())) {
            byId.put(run.getId(), run);
        }
        return byId;
    }

    private void publishMetrics(List<ForecastPoint> batch, Map<UUID, ForecastRun> runsById) {
        for (ForecastPoint point : batch) {
            ForecastRun run = runsById.get(point.getRunId());
            if (run == null) {
                continue;
            }
            live.publishAfterCommit(run.getDeviceId(), "metrics", point.getTargetAt(), Instant.now(),
                    new MetricsUpdate(run.getDeviceId(), point.getHorizonMinutes(),
                            point.getActualPm25(), point.getError()));
        }
    }

    /** Envelope payload for the {@code metrics} SSE event (io_contract §12.2, §13). */
    public record MetricsUpdate(String deviceId, Integer horizonMinutes, Float actualPm25, Float error) {
    }
}
