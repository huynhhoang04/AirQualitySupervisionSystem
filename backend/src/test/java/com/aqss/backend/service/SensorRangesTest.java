package com.aqss.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensorRangesTest {

    @Test
    @DisplayName("PM2.5 is rejected below zero and above the ~750 µg/m³ saturation point")
    void should_RejectPm25_When_OutOfRejectRange() {
        assertThat(SensorRanges.pm25Rejected(-0.1f)).isTrue();
        assertThat(SensorRanges.pm25Rejected(0f)).isFalse();
        assertThat(SensorRanges.pm25Rejected(500f)).isFalse();
        assertThat(SensorRanges.pm25Rejected(750f)).isFalse();
        assertThat(SensorRanges.pm25Rejected(750.1f)).isTrue();
    }

    @Test
    @DisplayName("CO2 is rejected outside [300, 10000] but only alerts above 1000 ppm")
    void should_SeparateCo2RejectRangeFromAlertThreshold() {
        assertThat(SensorRanges.co2Rejected(299f)).isTrue();
        assertThat(SensorRanges.co2Rejected(300f)).isFalse();
        assertThat(SensorRanges.co2Alert(1000f)).isFalse();
        assertThat(SensorRanges.co2Alert(1001f)).isTrue();
    }

    @Test
    @DisplayName("Temperature and humidity follow the shared validation table")
    void should_RejectTemperatureAndHumidity_When_OutOfRejectRange() {
        assertThat(SensorRanges.temperatureRejected(-40.1f)).isTrue();
        assertThat(SensorRanges.temperatureRejected(60f)).isFalse();
        assertThat(SensorRanges.temperatureRejected(85.1f)).isTrue();
        assertThat(SensorRanges.humidityRejected(-1f)).isTrue();
        assertThat(SensorRanges.humidityRejected(100f)).isFalse();
        assertThat(SensorRanges.humidityRejected(100.1f)).isTrue();
    }
}
