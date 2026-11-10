package com.aqss.backend.service;

import com.aqss.backend.model.Space;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdServiceTest {

    private final ThresholdService thresholds = new ThresholdService();

    private static Space space(String purpose, String ventilation) {
        Space space = new Space();
        space.setCode("SP");
        space.setName("Test");
        space.setPurpose(purpose);
        space.setVentilationEstimate(ventilation);
        space.setVolumeM3(20f);
        return space;
    }

    @Test
    @DisplayName("A bedroom is stricter than a living room: same air, breathed twice as long")
    void bedroomIsStricterThanLivingRoom() {
        double bedroom = thresholds.compute(space("bedroom", "medium"), null).thresholdPm25();
        double living = thresholds.compute(space("living_room", "medium"), null).thresholdPm25();
        assertThat(bedroom).isLessThan(living);
    }

    @Test
    @DisplayName("A sealed room (high CO2) gets a lower threshold than a ventilated one")
    void highCo2LowersTheThreshold() {
        Space room = space("bedroom", "medium");
        double fresh = thresholds.compute(room, 450.0).thresholdPm25();
        double sealed = thresholds.compute(room, 1200.0).thresholdPm25();
        assertThat(sealed).isLessThan(fresh);
    }

    @Test
    @DisplayName("No CO2 reading means no penalty: the profile decides alone")
    void missingCo2DoesNotChangeAnything() {
        Space room = space("office", "high");
        assertThat(thresholds.compute(room, null).co2Factor()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Poor ventilation pulls the threshold down, good ventilation pushes it up")
    void ventilationMovesTheThreshold() {
        double low = thresholds.compute(space("office", "low"), null).thresholdPm25();
        double high = thresholds.compute(space("office", "high"), null).thresholdPm25();
        assertThat(low).isLessThan(high);
    }

    @Test
    @DisplayName("The result always lands inside the sensor's usable band")
    void resultStaysInRange() {
        for (String purpose : new String[]{"bedroom", "classroom", "office", "living_room", "kitchen", null}) {
            for (String ventilation : new String[]{"low", "medium", "high", null}) {
                double value = thresholds.compute(space(purpose, ventilation), 2000.0).thresholdPm25();
                assertThat(value).isBetween(ThresholdService.MIN_THRESHOLD, ThresholdService.MAX_THRESHOLD);
            }
        }
    }

    @Test
    @DisplayName("A larger room tolerates a higher threshold than a cramped one")
    void volumeRaisesTheThreshold() {
        Space small = space("bedroom", "low");
        small.setVolumeM3(12f);
        Space large = space("bedroom", "low");
        large.setVolumeM3(45f);
        assertThat(thresholds.compute(large, null).thresholdPm25())
                .isGreaterThan(thresholds.compute(small, null).thresholdPm25());
    }

    @Test
    @DisplayName("Volume cannot push a room past the health ceiling")
    void volumeRespectsCeiling() {
        Space huge = space("living_room", "high");
        huge.setVolumeM3(5000f);
        assertThat(thresholds.compute(huge, null).thresholdPm25())
                .isEqualTo(ThresholdService.MAX_THRESHOLD);
    }

    @Test
    @DisplayName("The reason is reported, not just the number")
    void reportsItsInputs() {
        ThresholdService.Result result = thresholds.compute(space("bedroom", "low"), 900.0);
        assertThat(result.occupancyHours()).isEqualTo(8.0);
        assertThat(result.ventilationFactor()).isLessThan(1.0);
        assertThat(result.co2Factor()).isLessThan(1.0);
        assertThat(result.volumeM3()).isEqualTo(20f);
        assertThat(result.volumeFactor()).isEqualTo(1.0);
    }
}
