package com.aqss.backend.service.rules;

import com.aqss.backend.entity.Space;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdRuleTest {

    private final ThresholdRule rule = new ThresholdRule();

    private static Space space(String purpose, float volume, String ventilation) {
        return Space.builder()
                .code("SP_T")
                .name("Test")
                .volumeM3(volume)
                .purpose(purpose)
                .region("hanoi_caugiay")
                .ventilationEstimate(ventilation)
                .build();
    }

    @Test
    @DisplayName("A small sealed bedroom gets a stricter threshold than a large ventilated classroom")
    void should_BeStricterForSealedBedroom_When_ComparedToClassroom() {
        ThresholdResult bedroom = rule.compute(space("bedroom", 15f, "low"));
        ThresholdResult classroom = rule.compute(space("classroom", 450f, "high"));

        assertThat(bedroom.baseThresholdPm25()).isLessThan(classroom.baseThresholdPm25());
    }

    @Test
    @DisplayName("Bigger volumes and better ventilation relax the threshold")
    void should_RelaxThreshold_When_VolumeOrVentilationIncrease() {
        double small = rule.compute(space("office", 15f, "low")).baseThresholdPm25();
        double big = rule.compute(space("office", 300f, "low")).baseThresholdPm25();
        double ventilated = rule.compute(space("office", 15f, "high")).baseThresholdPm25();

        assertThat(big).isGreaterThan(small);
        assertThat(ventilated).isGreaterThan(small);
    }

    @Test
    @DisplayName("Output is always inside the [10, 50] µg/m³ envelope with an ordered band")
    void should_StayWithinEnvelope_When_ParametersAreExtreme() {
        ThresholdResult result = rule.compute(space("classroom", 5000f, "high"));

        assertThat(result.baseThresholdPm25()).isBetween(10.0, 50.0);
        assertThat(result.uncertaintyLower()).isLessThanOrEqualTo(result.baseThresholdPm25());
        assertThat(result.uncertaintyUpper()).isGreaterThanOrEqualTo(result.baseThresholdPm25());
        assertThat(result.modelVersion()).isEqualTo(ThresholdResult.RULE_MODEL_VERSION);
    }
}
