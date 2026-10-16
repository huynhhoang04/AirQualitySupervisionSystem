package com.aqss.backend.service.rules;

import com.aqss.backend.entity.Space;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DosageRuleTest {

    private static final double THRESHOLD = 15.0;
    private static final double CADR = 40.0;
    private static final double OUTDOOR = 10.0;
    private static final double PENETRATION = 0.8;

    private final DosageRule rule = new DosageRule();

    private static Space space(float volume) {
        return Space.builder().code("SP_T").name("Test").volumeM3(volume)
                .purpose("bedroom").region("hanoi_caugiay").ventilationEstimate("low").build();
    }

    @Test
    @DisplayName("The fan level escalates with the ratio of current PM2.5 to the threshold")
    void should_EscalateLevel_When_RatioIncreases() {
        short justBreaching = rule.compute(space(15f), THRESHOLD * 1.05, THRESHOLD, CADR, OUTDOOR, PENETRATION).level();
        short moderate = rule.compute(space(15f), THRESHOLD * 1.5, THRESHOLD, CADR, OUTDOOR, PENETRATION).level();
        short severe = rule.compute(space(15f), THRESHOLD * 2.5, THRESHOLD, CADR, OUTDOOR, PENETRATION).level();

        assertThat(justBreaching).isEqualTo((short) 1);
        assertThat(moderate).isEqualTo((short) 2);
        assertThat(severe).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("Duration stays inside the [5, 120] minute actuation window")
    void should_ClampDuration_When_CleanUpTimeModelIsExtreme() {
        DosageResult fast = rule.compute(space(5f), THRESHOLD * 1.2, THRESHOLD, 500.0, 5.0, PENETRATION);
        DosageResult slow = rule.compute(space(500f), THRESHOLD * 3.0, THRESHOLD, 20.0, 5.0, PENETRATION);

        assertThat(fast.durationMinutes()).isBetween(5, 120);
        assertThat(slow.durationMinutes()).isBetween(5, 120);
    }

    @Test
    @DisplayName("A target below the outdoor-equivalent concentration yields the minimum run")
    void should_ReturnMinimumDuration_When_TargetIsUnreachable() {
        DosageResult result = rule.compute(space(15f), 42.0, 12.8, 40.0, 35.0, 0.8);

        assertThat(result.durationMinutes()).isEqualTo(5);
        assertThat(result.level()).isEqualTo((short) 3);
        assertThat(result.modelVersion()).isEqualTo(DosageResult.RULE_MODEL_VERSION);
    }
}
