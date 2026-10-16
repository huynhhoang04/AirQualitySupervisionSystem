package com.aqss.backend.service;

/**
 * The one validation table enforced identically at every layer (io_contract §12).
 * "Reject if" bounds are wider than the declared valid range so that saturated/garbage
 * readings are discarded rather than stored.
 */
public final class SensorRanges {

    /** GP2Y1023AU0F saturates near 750 µg/m³ — above the 500 µg/m³ valid maximum. */
    public static final float PM25_SATURATION = 750f;
    public static final float CO2_ALERT_PPM = 1000f;

    private SensorRanges() {
    }

    public static boolean pm25Rejected(float value) {
        return value < 0f || value > PM25_SATURATION;
    }

    public static boolean pm25Saturated(float value) {
        return value > PM25_SATURATION;
    }

    public static boolean co2Rejected(float value) {
        return value < 300f || value > 10_000f;
    }

    public static boolean temperatureRejected(float value) {
        return value < -40f || value > 85f;
    }

    public static boolean humidityRejected(float value) {
        return value < 0f || value > 100f;
    }

    public static boolean co2Alert(float value) {
        return value > CO2_ALERT_PPM;
    }
}
