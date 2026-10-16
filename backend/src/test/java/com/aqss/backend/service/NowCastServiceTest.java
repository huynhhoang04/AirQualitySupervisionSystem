package com.aqss.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NowCastServiceTest {

    private final NowCastService nowCastService = new NowCastService();

    @Test
    @DisplayName("AQI breakpoint boundaries map to the EPA category edges")
    void should_MapConcentrationToBreakpointEdges_When_AtBoundary() {
        assertThat(nowCastService.aqiFromConcentration(0.0)).isZero();
        assertThat(nowCastService.aqiFromConcentration(12.0)).isEqualTo(50);
        assertThat(nowCastService.aqiFromConcentration(35.4)).isEqualTo(100);
        assertThat(nowCastService.aqiFromConcentration(35.5)).isEqualTo(101);
        assertThat(nowCastService.aqiFromConcentration(55.4)).isEqualTo(150);
        assertThat(nowCastService.aqiFromConcentration(500.4)).isEqualTo(500);
    }

    @Test
    @DisplayName("Concentrations above the table are clamped to the hazardous maximum")
    void should_ClampTo500_When_ConcentrationAboveTable() {
        assertThat(nowCastService.aqiFromConcentration(900.0)).isEqualTo(500);
    }

    @Test
    @DisplayName("Sensitive-groups label is the full 30-character category, not a truncation")
    void should_ReturnFullCategoryLabel_When_AqiInSensitiveGroupBand() {
        assertThat(nowCastService.aqiLevel(101)).isEqualTo("Unhealthy for Sensitive Groups");
        assertThat(nowCastService.aqiLevel(50)).isEqualTo("Good");
        assertThat(nowCastService.aqiLevel(500)).isEqualTo("Hazardous");
    }

    @Test
    @DisplayName("A constant series reduces to the plain breakpoint lookup")
    void should_MatchPlainLookup_When_SeriesIsConstant() {
        assertThat(nowCastService.computeAqi(List.of(20.0, 20.0, 20.0, 20.0)))
                .isEqualTo(nowCastService.aqiFromConcentration(20.0));
    }

    @Test
    @DisplayName("Empty or single-point series degrade gracefully")
    void should_HandleDegenerateSeries_When_EmptyOrSinglePoint() {
        assertThat(nowCastService.computeAqi(List.of())).isZero();
        assertThat(nowCastService.computeAqi(List.of(30.0))).isEqualTo(nowCastService.aqiFromConcentration(30.0));
    }
}
