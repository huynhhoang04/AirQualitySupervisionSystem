package com.aqss.backend.service.rules;

import com.aqss.backend.entity.Space;
import org.springframework.stereotype.Component;

/**
 * Rule-based stand-in for ML#2 (dosage), used while the Layer 3 AI service is unavailable.
 * Duration comes from the clean-up time of a well-mixed single-zone model:
 *
 * <pre>t = V / CADR_eff · ln((C − C_out·P) / (C_target − C_out·P))</pre>
 *
 * with {@code C_target = 0.7 × threshold} (the hysteresis release point).
 */
@Component
public class DosageRule {

    private static final double[] DUTY_FRACTION = {0.0, 0.35, 0.60, 0.90};
    private static final int MIN_DURATION_MIN = 5;
    private static final int MAX_DURATION_MIN = 120;

    public DosageResult compute(Space space, double currentPm25, double baseThreshold,
                                double filterCadr, double outdoorPm25, double penetrationFactor) {
        short level = levelFor(currentPm25, baseThreshold);
        double cadrEffective = Math.max(1.0, filterCadr * DUTY_FRACTION[level]);
        double target = baseThreshold * 0.7;
        double outdoorIndoor = outdoorPm25 * penetrationFactor;

        double numerator = currentPm25 - outdoorIndoor;
        double denominator = target - outdoorIndoor;
        double duration;
        if (numerator <= 0 || denominator <= 0 || numerator <= denominator) {
            duration = MIN_DURATION_MIN;
        } else {
            double volume = space.getVolumeM3() == null ? 30.0 : space.getVolumeM3();
            duration = volume / cadrEffective * Math.log(numerator / denominator) * 60.0;
        }
        int minutes = (int) Math.round(clamp(duration, MIN_DURATION_MIN, MAX_DURATION_MIN));
        return new DosageResult(level, minutes, DosageResult.RULE_MODEL_VERSION);
    }

    /** Discreet levels keep the BLDC fan above its ~20 % stall duty. */
    private short levelFor(double currentPm25, double baseThreshold) {
        if (baseThreshold <= 0) {
            return 1;
        }
        double ratio = currentPm25 / baseThreshold;
        if (ratio < 1.2) {
            return 1;
        }
        if (ratio < 1.7) {
            return 2;
        }
        return 3;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
