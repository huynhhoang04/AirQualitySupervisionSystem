package com.aqss.backend.service;

import com.aqss.backend.model.ForecastRun;
import com.aqss.backend.repo.ForecastPointRepository;
import com.aqss.backend.repo.ForecastRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * How well the forecasts already issued matched what the room measured (io_contract §13).
 *
 * <p>The cohort is one device, one model version and one horizon; mixing versions would silently
 * average a better model with a worse one. Nothing here is extrapolated: an empty cohort reports
 * zero matches rather than 100 %.
 */
@Service
@RequiredArgsConstructor
public class ForecastAccuracyService {

    /** Tolerance for "within". It is shown next to the percentage, never hidden behind it. */
    public static final float TOLERANCE_UG_M3 = 5f;

    private static final List<Integer> HORIZONS = List.of(5, 15, 30);

    private final ForecastPointRepository points;
    private final ForecastRunRepository runs;

    @Transactional(readOnly = true)
    public Accuracy accuracy(String deviceId, int horizonMinutes, int hours, String modelVersion) {
        if (!HORIZONS.contains(horizonMinutes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "horizon must be 5, 15 or 30 minutes");
        }
        int window = Math.max(1, Math.min(hours, 24 * 14));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(window));
        String model = resolveModel(deviceId, modelVersion);

        List<ForecastPointRepository.HorizonAggregate> rows = points.aggregate(
                deviceId, model, from, to, TOLERANCE_UG_M3);
        ForecastPointRepository.HorizonAggregate row = rows.stream()
                .filter(item -> horizonMinutes == item.getHorizonMinutes())
                .findFirst()
                .orElse(null);

        long matched = row == null ? 0 : nullSafe(row.getMatched());
        long within = row == null ? 0 : nullSafe(row.getWithin());
        long pending = row == null ? 0 : nullSafe(row.getPending());
        long missing = row == null ? 0 : nullSafe(row.getMissing());
        long late = row == null ? 0 : nullSafe(row.getLate());
        Double mae = row == null || row.getMae() == null ? null : round(row.getMae());

        List<HorizonSummary> horizons = HORIZONS.stream()
                .map(value -> summarize(rows, value))
                .toList();

        return new Accuracy(deviceId, model, horizonMinutes, window, from, to,
                matched, mae, within, matched == 0 ? null : round(100.0 * within / matched),
                pending, missing, late, TOLERANCE_UG_M3, horizons);
    }

    /** The drawer behind "Xem các mốc": newest scored pairs, newest first. */
    @Transactional(readOnly = true)
    public List<ScoredForecastPoint> recent(String deviceId, int horizonMinutes, int hours,
                                            String modelVersion, int limit) {
        String model = resolveModel(deviceId, modelVersion);
        Instant from = Instant.now().minus(Duration.ofHours(Math.max(1, Math.min(hours, 24 * 14))));
        Integer horizon = horizonMinutes == 0 ? null : horizonMinutes;
        return points.recentScored(deviceId, model, from, horizon,
                PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    /**
     * Default cohort: whatever produced the latest run. Asking for a specific version is how the
     * panel keeps the number honest when the model is swapped mid-window.
     */
    private String resolveModel(String deviceId, String modelVersion) {
        if (modelVersion != null && !modelVersion.isBlank()) {
            return modelVersion;
        }
        ForecastRun latest = runs.findFirstByDeviceIdOrderByIssuedAtDesc(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no forecast for " + deviceId));
        return latest.getModelVersion();
    }

    private static HorizonSummary summarize(List<ForecastPointRepository.HorizonAggregate> rows, int horizon) {
        ForecastPointRepository.HorizonAggregate row = rows.stream()
                .filter(item -> horizon == item.getHorizonMinutes())
                .findFirst()
                .orElse(null);
        if (row == null) {
            return new HorizonSummary(horizon, 0, null, null, 0, 0, 0);
        }
        long matched = nullSafe(row.getMatched());
        long within = nullSafe(row.getWithin());
        return new HorizonSummary(horizon, matched,
                row.getMae() == null ? null : round(row.getMae()),
                matched == 0 ? null : round(100.0 * within / matched),
                nullSafe(row.getPending()), nullSafe(row.getMissing()), nullSafe(row.getLate()));
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    private static Double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record HorizonSummary(int horizonMinutes, long matched, Double maeUgM3,
                                 Double withinTolerancePct, long pending, long missing, long late) {
    }

    public record Accuracy(String deviceId, String modelVersion, int horizonMinutes, int windowHours,
                           Instant from, Instant to, long matched, Double maeUgM3,
                           long withinTolerance, Double withinTolerancePct, long pending, long missing,
                           long late, float toleranceUgM3, List<HorizonSummary> horizons) {
    }
}
