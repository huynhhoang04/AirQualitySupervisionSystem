package com.aqss.backend.service.rules;

import com.aqss.backend.entity.Space;
import org.springframework.stereotype.Component;

/**
 * Rule-based stand-in for ML#1 (space → PM2.5 threshold), used while the Layer 3 AI service is
 * unavailable. Encodes the same physics/standard intuition the surrogate model learns: a small,
 * sealed, long-occupancy room earns a stricter threshold than a large, well-ventilated one.
 *
 * <p>Replaced by {@code AiClient} once Phase 9 lands.
 */
@Component
public class ThresholdRule {

    private static final double WHO_24H = 15.0;
    private static final double MIN_THRESHOLD = 10.0;
    private static final double MAX_THRESHOLD = 50.0;

    public ThresholdResult compute(Space space) {
        double base = WHO_24H * purposeFactor(space.getPurpose());
        base *= volumeFactor(space.getVolumeM3());
        base *= ventilationFactor(space.getVentilationEstimate());
        base = clamp(base, MIN_THRESHOLD, MAX_THRESHOLD);

        double lower = clamp(base * 0.80, MIN_THRESHOLD, MAX_THRESHOLD);
        double upper = clamp(base * 1.25, MIN_THRESHOLD, MAX_THRESHOLD);
        return new ThresholdResult(round(base), round(lower), round(upper), ThresholdResult.RULE_MODEL_VERSION);
    }

    /** Long continuous exposure (sleeping) is stricter than transient occupancy. */
    private double purposeFactor(String purpose) {
        return switch (purpose == null ? "" : purpose.toLowerCase()) {
            case "bedroom", "dorm" -> 1.20;
            case "classroom" -> 2.00;
            case "office" -> 2.20;
            default -> 1.50;
        };
    }

    /** Bigger volumes dilute indoor sources, so a higher concentration is tolerable. */
    private double volumeFactor(Float volumeM3) {
        double volume = volumeM3 == null ? 30.0 : volumeM3;
        return clamp(0.70 + 0.30 * (volume / 50.0), 0.70, 1.30);
    }

    /** Higher air exchange removes pollution faster, so the threshold can be relaxed. */
    private double ventilationFactor(String ventilation) {
        return switch (ventilation == null ? "low" : ventilation.toLowerCase()) {
            case "high" -> 1.15;
            case "medium" -> 1.00;
            default -> 0.90;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
