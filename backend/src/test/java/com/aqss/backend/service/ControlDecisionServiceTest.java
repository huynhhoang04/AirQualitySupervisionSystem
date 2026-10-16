package com.aqss.backend.service;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.dto.forecast.ForecastPointDto;
import com.aqss.backend.entity.ControlDecision;
import com.aqss.backend.entity.DecisionAction;
import com.aqss.backend.entity.DecisionMode;
import com.aqss.backend.entity.Space;
import com.aqss.backend.entity.SpaceThreshold;
import com.aqss.backend.service.rules.DosageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the control-loop branching (no Spring context). */
class ControlDecisionServiceTest {

    private static final double THRESHOLD = 15.0;

    private final SpaceService spaceService = mock(SpaceService.class);
    private final ThresholdService thresholdService = mock(ThresholdService.class);
    private final DosageService dosageService = mock(DosageService.class);
    private final ForecastService forecastService = mock(ForecastService.class);
    private final TelemetryQueryService telemetryQueryService = mock(TelemetryQueryService.class);
    private final ActuatorService actuatorService = mock(ActuatorService.class);
    private final DecisionAuditService auditService = mock(DecisionAuditService.class);
    private final AlertService alertService = mock(AlertService.class);

    private final AqssProperties properties = new AqssProperties(
            new AqssProperties.Ai("http://localhost:8000", 30_000, false),
            new AqssProperties.Control(60_000, "0 0 * * * *", 0.7, 5, 3, 120, 10, 40.0, 0.8, 35.0),
            new AqssProperties.Telemetry(1000, 10_000, 5));

    private final ControlDecisionService service = new ControlDecisionService(
            spaceService, thresholdService, dosageService, forecastService,
            telemetryQueryService, actuatorService, auditService, alertService, properties);

    private final Space space = Space.builder()
            .id(1L).code("SP_201").name("Phòng 201").volumeM3(15f)
            .purpose("bedroom").region("hanoi_caugiay").ventilationEstimate("low").build();

    @BeforeEach
    void stubThresholdAndAudit() {
        when(thresholdService.getOrCompute(space)).thenReturn(
                SpaceThreshold.builder().spaceId(1L).baseThresholdPm25((float) THRESHOLD).build());
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ControlDecision.builder()
                        .action(invocation.getArgument(1))
                        .mode(invocation.getArgument(2))
                        .build());
        when(forecastService.latestFor(space)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("A breach doses the space and audits an AUTO ACTIVATE")
    void should_ActivateAndAudit_When_Pm25ExceedsThreshold() {
        DosageResult dosage = new DosageResult((short) 2, 25, "rule-v2.0");
        when(telemetryQueryService.rollingMedianPm25("SP_201")).thenReturn(20.0);
        when(actuatorService.isOn(space)).thenReturn(false);
        when(dosageService.compute(space, 20.0, THRESHOLD)).thenReturn(dosage);
        when(actuatorService.activate(space, dosage)).thenReturn(true);

        Optional<ControlDecision> decision = service.evaluate(space);

        assertThat(decision).isPresent();
        assertThat(decision.get().getAction()).isEqualTo(DecisionAction.ACTIVATE);
        verify(auditService).record(space, DecisionAction.ACTIVATE, DecisionMode.AUTO,
                "threshold_breach", 20.0, THRESHOLD, (short) 2, 25);
        verify(alertService).raise(eq(1L), eq(null), any(), any(), any());
    }

    @Test
    @DisplayName("Without a recent reading the cycle is a no-op")
    void should_DoNothing_When_NoRecentTelemetry() {
        when(telemetryQueryService.rollingMedianPm25("SP_201")).thenReturn(null);

        assertThat(service.evaluate(space)).isEmpty();
        verify(actuatorService, never()).activate(any(), any());
    }

    @Test
    @DisplayName("Falling below 70 % of the threshold releases the fan (hysteresis)")
    void should_DeactivateAndAudit_When_Pm25BelowHysteresis() {
        when(telemetryQueryService.rollingMedianPm25("SP_201")).thenReturn(5.0);
        when(actuatorService.isOn(space)).thenReturn(true);
        when(actuatorService.onDurationMinutes(space)).thenReturn(1.0);
        when(actuatorService.deactivate(space, false)).thenReturn(true);

        Optional<ControlDecision> decision = service.evaluate(space);

        assertThat(decision).isPresent();
        assertThat(decision.get().getAction()).isEqualTo(DecisionAction.DEACTIVATE);
        verify(auditService).record(space, DecisionAction.DEACTIVATE, DecisionMode.AUTO,
                "hysteresis_off", 5.0, THRESHOLD, null, null);
    }

    @Test
    @DisplayName("Exceeding the maximum ON time force-deactivates the fan")
    void should_ForceDeactivate_When_MaxOnMinutesExceeded() {
        when(telemetryQueryService.rollingMedianPm25("SP_201")).thenReturn(30.0);
        when(actuatorService.isOn(space)).thenReturn(true);
        when(actuatorService.onDurationMinutes(space)).thenReturn(200.0);
        when(actuatorService.deactivate(space, true)).thenReturn(true);

        Optional<ControlDecision> decision = service.evaluate(space);

        assertThat(decision).isPresent();
        verify(auditService).record(space, DecisionAction.DEACTIVATE, DecisionMode.AUTO,
                "max_on_exceeded", 30.0, THRESHOLD, null, null);
    }

    @Test
    @DisplayName("A q90 breach within two hours warns the node before the threshold is crossed")
    void should_WarnAndAudit_When_ForecastBreachesWithinTwoHours() {
        when(telemetryQueryService.rollingMedianPm25("SP_201")).thenReturn(12.0);
        ForecastPointDto breaching = new ForecastPointDto(
                Instant.now().plus(60, ChronoUnit.MINUTES), 18f, 14f, 22f);
        when(forecastService.latestFor(space)).thenReturn(
                Optional.of(new Forecast(Instant.now(), "v2.0", List.of(breaching))));

        Optional<ControlDecision> decision = service.evaluate(space);

        assertThat(decision).isPresent();
        assertThat(decision.get().getMode()).isEqualTo(DecisionMode.ALERT);
        verify(actuatorService).warn(eq(space), anyDouble());
        verify(auditService).record(space, DecisionAction.ALERT, DecisionMode.ALERT,
                "forecast_breach_q90", 12.0, THRESHOLD, null, null);
        verify(actuatorService, never()).activate(any(), any());
    }
}
