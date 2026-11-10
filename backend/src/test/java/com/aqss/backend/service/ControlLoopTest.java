package com.aqss.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ControlLoopTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static ControlLoop.Decision decide(double pm25, double threshold, boolean fanOn,
                                               Instant onSince, Instant offSince) {
        return ControlLoop.decide(new ControlLoop.Inputs(pm25, threshold, fanOn, onSince, offSince,
                NOW, 5, 120, 0.7));
    }

    private static ControlLoop.Decision idle(double pm25, double threshold, long offMinutesAgo) {
        return decide(pm25, threshold, false, null, NOW.minus(offMinutesAgo, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("Breaching the threshold turns an idle fan on immediately")
    void should_Activate_When_Pm25ReachesThreshold() {
        assertThat(idle(36, 35, 10)).isEqualTo(ControlLoop.Decision.ACTIVATE);
    }

    @Test
    @DisplayName("Activation is not delayed by the motor-rest window")
    void should_Activate_EvenWhen_FanJustStopped() {
        // One minute after stopping, a breach still turns the fan on: waiting is not protection.
        assertThat(idle(90, 35, 1)).isEqualTo(ControlLoop.Decision.ACTIVATE);
        assertThat(idle(90, 35, 0)).isEqualTo(ControlLoop.Decision.ACTIVATE);
    }

    @Test
    @DisplayName("Staying below the threshold changes nothing")
    void should_DoNothing_When_BelowThreshold() {
        assertThat(idle(20, 35, 10)).isEqualTo(ControlLoop.Decision.NONE);
    }

    @Test
    @DisplayName("Hysteresis keeps the fan on until PM2.5 drops below 70 % of the threshold")
    void should_KeepRunning_When_AboveHysteresis() {
        assertThat(decide(30, 35, true, NOW.minus(10, ChronoUnit.MINUTES), null))
                .isEqualTo(ControlLoop.Decision.NONE);
        assertThat(decide(24, 35, true, NOW.minus(10, ChronoUnit.MINUTES), null))
                .isEqualTo(ControlLoop.Decision.DEACTIVATE);
    }

    @Test
    @DisplayName("The minimum ON time blocks an early deactivation")
    void should_KeepRunning_When_MinOnTimeNotElapsed() {
        assertThat(decide(10, 35, true, NOW.minus(2, ChronoUnit.MINUTES), null))
                .isEqualTo(ControlLoop.Decision.NONE);
    }

    @Test
    @DisplayName("The maximum ON time stops the fan even if the air is still bad")
    void should_Stop_When_MaxOnElapsed() {
        assertThat(decide(90, 35, true, NOW.minus(121, ChronoUnit.MINUTES), null))
                .isEqualTo(ControlLoop.Decision.DEACTIVATE);
    }
}
