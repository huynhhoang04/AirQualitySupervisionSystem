package com.aqss.backend.service;

import com.aqss.backend.model.Space;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Where a room's threshold comes from, and why it is not a number someone guessed.
 *
 * <p>The model is a mass balance over the room:
 *
 * <pre>
 *   V dC/dt = P·C_out·λ·V − (λ·V + CADR)·C + G
 * </pre>
 *
 * <p>With no filter running, the room settles at a steady state, and the question "how poor may the
 * air get before it matters" is a dose question: a person in the room accumulates
 * {@code C × hours}. Dividing the daily guideline by the hours actually spent in the room is what
 * makes a bedroom stricter than a lobby — the same air, breathed four times as long.
 *
 * <p>Two corrections keep the number honest:
 * <ul>
 *   <li><b>Ventilation</b> — a well-ventilated room removes particles on its own, so it tolerates a
 *       higher peak; a sealed one does not.</li>
 *   <li><b>Live CO₂</b> — CO₂ is a proxy for how much fresh air the room is actually getting right
 *       now. High CO₂ means the room is sealed and occupied, which is exactly when a lower threshold
 *       is warranted. This is why the node carries a CO₂ sensor at all.</li>
 * </ul>
 *
 * <p>The constants are the project's own policy, not measurements: the daily target is the WHO 2021
 * 24-hour guideline (15 µg/m³), the occupancy hours are a per-purpose assumption an operator can
 * override with the room's own volume and purpose, and the ventilation multipliers are a coarse
 * mapping of the "low/medium/high" field. Every computed threshold reports which inputs produced it
 * so the dashboard can show its work.
 */
@Service
@Slf4j
public class ThresholdService {

    /** WHO 2021 air-quality guideline, 24-hour mean, PM2.5. */
    static final double DAILY_TARGET_UGM3 = 15.0;

    /** Hours per day a person typically spends in each kind of room. */
    static final double DEFAULT_OCCUPANCY_HOURS = 8.0;

    /** How much the room's own air exchange helps; low ventilation means the room needs a lower bar. */
    static final double VENTILATION_FACTOR_LOW = 0.75;
    static final double VENTILATION_FACTOR_MEDIUM = 1.0;
    static final double VENTILATION_FACTOR_HIGH = 1.35;

    /** A 20 m³ room is the reference; the factor is the cube root of the ratio, clamped. */
    static final double REFERENCE_VOLUME_M3 = 20.0;
    static final double VOLUME_FACTOR_MIN = 0.7;
    static final double VOLUME_FACTOR_MAX = 1.6;

    /** CO₂ above the outdoor baseline shows how sealed and occupied the room is. */
    static final double CO2_BASELINE_PPM = 420.0;
    /** At this much CO₂ above baseline the room is treated as fully occupied and poorly ventilated. */
    static final double CO2_SEALED_DELTA = 800.0;
    /** A sealed room gets its threshold pulled down by at most this fraction. */
    static final double CO2_MAX_CORRECTION = 0.25;

    static final double MIN_THRESHOLD = 10.0;
    /**
     * Ceiling, and it is a health rule rather than a formula result: above ~60 µg/m³ the air is
     * "unhealthy for sensitive groups" on every index the project quotes. A room may be large and
     * rarely used, but it must not be allowed to drift to a level that is bad for anyone in it.
     */
    static final double MAX_THRESHOLD = 60.0;

    /**
     * @param space   the room whose profile to use; null fields fall back to the defaults above
     * @param co2Ppm  the room's current CO₂, or null when the sensor has no reading yet
     */
    public Result compute(Space space, Double co2Ppm) {
        double occupancyHours = space.getOccupancyHours() != null
                ? clampHours(space.getOccupancyHours())
                : occupancyHours(space.getPurpose());
        double ventilation = ventilationFactor(space.getVentilationEstimate());
        double volume = volumeFactor(space.getVolumeM3());
        double co2Factor = co2Factor(co2Ppm);

        // Dose split over the occupied window: the shorter the stay, the higher a peak it tolerates.
        // The square root keeps a seldom-used room from being allowed six times the concentration of
        // a bedroom — it still tolerates more, just not absurdly so.
        double dose = DAILY_TARGET_UGM3 * Math.sqrt(24.0 / occupancyHours);
        // The room's own dilution capacity matters before the fan ever runs: a large, well-ventilated
        // room reaches the same concentration more slowly and clears sooner.
        double threshold = dose * ventilation * volume * co2Factor;
        threshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, threshold));

        return new Result(
                round(threshold),
                round(dose),
                round(ventilation),
                round(volume),
                round(co2Factor),
                occupancyHours,
                space.getVolumeM3(),
                co2Ppm);
    }

    /**
     * How much the room's size buys it, independent of ventilation.
     *
     * <p>A larger room needs proportionally more emitted mass to reach the same concentration, and
     * its air changes turn over more per hour in absolute terms. The factor is deliberately gentle
     * (a cube root, capped either side) so a big room is not treated as a different kind of room.
     */
    static double volumeFactor(Float volumeM3) {
        if (volumeM3 == null || volumeM3 <= 0) {
            return 1.0;
        }
        double ratio = volumeM3 / REFERENCE_VOLUME_M3;
        double factor = Math.cbrt(ratio);
        return Math.max(VOLUME_FACTOR_MIN, Math.min(VOLUME_FACTOR_MAX, factor));
    }

    /** A bedroom is occupied longer than a lobby, so its air must be cleaner. */
    static double occupancyHours(String purpose) {
        if (purpose == null) {
            return DEFAULT_OCCUPANCY_HOURS;
        }
        return switch (purpose.toLowerCase()) {
            case "bedroom", "dorm" -> 8.0;
            case "classroom" -> 6.0;
            case "office" -> 8.0;
            case "living_room" -> 4.0;
            case "kitchen" -> 2.0;
            default -> DEFAULT_OCCUPANCY_HOURS;
        };
    }

    /** An explicit operator value wins, but must stay a plausible number of hours per day. */
    static double clampHours(int hours) {
        return Math.max(1, Math.min(hours, 24));
    }

    static double ventilationFactor(String estimate) {
        if (estimate == null) {
            return VENTILATION_FACTOR_MEDIUM;
        }
        return switch (estimate.toLowerCase()) {
            case "low" -> VENTILATION_FACTOR_LOW;
            case "high" -> VENTILATION_FACTOR_HIGH;
            default -> VENTILATION_FACTOR_MEDIUM;
        };
    }

    /**
     * CO₂ above outdoor baseline, normalised. No reading means no correction: the threshold falls
     * back to the room profile rather than inventing a sealed-room penalty.
     */
    static double co2Factor(Double co2Ppm) {
        if (co2Ppm == null) {
            return 1.0;
        }
        double delta = Math.max(0.0, co2Ppm - CO2_BASELINE_PPM);
        double sealed = Math.min(1.0, delta / CO2_SEALED_DELTA);
        return 1.0 - CO2_MAX_CORRECTION * sealed;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Everything that produced the number, so the UI can explain it instead of asserting it. */
    public record Result(
            double thresholdPm25,
            double dailyDoseTarget,
            double ventilationFactor,
            double volumeFactor,
            double co2Factor,
            double occupancyHours,
            Float volumeM3,
            Double co2Ppm) {
    }
}
