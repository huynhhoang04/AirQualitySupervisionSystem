package com.aqss.backend.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AQI via the EPA NowCast algorithm — a display-only metric.
 *
 * <p>V1 applied 24-hour-average EPA breakpoints to <em>instantaneous</em> PM2.5, which is
 * methodologically wrong. NowCast instead takes a concentration-weighted average of the last
 * 12 hours, then applies the standard breakpoints. Control logic never uses AQI.
 */
@Service
public class NowCastService {

    private static final double[] BP_LO = {0.0, 12.1, 35.5, 55.5, 150.5, 250.5};
    private static final double[] BP_HI = {12.0, 35.4, 55.4, 150.4, 250.4, 500.4};
    private static final int[] I_LO = {0, 51, 101, 151, 201, 301};
    private static final int[] I_HI = {50, 100, 150, 200, 300, 500};
    private static final String[] LEVELS = {
            "Good", "Moderate", "Unhealthy for Sensitive Groups", "Unhealthy", "Very Unhealthy", "Hazardous"
    };

    /**
     * @param hourlyPm25 newest hour first, up to 12 values (median of each clock hour)
     */
    public int computeAqi(List<Double> hourlyPm25) {
        if (hourlyPm25 == null || hourlyPm25.isEmpty()) {
            return 0;
        }
        if (hourlyPm25.size() == 1) {
            return aqiFromConcentration(hourlyPm25.get(0));
        }
        double max = hourlyPm25.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = hourlyPm25.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double weight = max <= 0 ? 0.5 : Math.max(0.5, 1.0 - (min / max));

        double weightedSum = 0;
        double weightSum = 0;
        for (int i = 0; i < hourlyPm25.size(); i++) {
            double w = Math.pow(weight, i);
            weightedSum += w * hourlyPm25.get(i);
            weightSum += w;
        }
        return aqiFromConcentration(weightedSum / weightSum);
    }

    public int aqiFromConcentration(double pm25) {
        double c = Math.max(0.0, Math.min(pm25, BP_HI[BP_HI.length - 1]));
        for (int i = 0; i < BP_LO.length; i++) {
            if (c <= BP_HI[i]) {
                double aqi = (I_HI[i] - I_LO[i]) / (BP_HI[i] - BP_LO[i]) * (c - BP_LO[i]) + I_LO[i];
                return (int) Math.round(aqi);
            }
        }
        return 500;
    }

    public String aqiLevel(int aqi) {
        for (int i = 0; i < I_HI.length; i++) {
            if (aqi <= I_HI[i]) {
                return LEVELS[i];
            }
        }
        return LEVELS[LEVELS.length - 1];
    }
}
