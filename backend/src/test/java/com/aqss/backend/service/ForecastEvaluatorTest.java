package com.aqss.backend.service;

import com.aqss.backend.model.ForecastPoint;
import com.aqss.backend.model.ForecastRun;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.repo.ForecastPointRepository;
import com.aqss.backend.repo.ForecastRunRepository;
import com.aqss.backend.repo.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastEvaluatorTest {

    private static final Instant TARGET = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID RUN_ID = UUID.randomUUID();

    private ForecastPointRepository points;
    private ForecastRunRepository runs;
    private TelemetryRepository telemetry;
    private LiveEventService live;
    private ForecastEvaluator evaluator;

    @BeforeEach
    void setUp() {
        points = mock(ForecastPointRepository.class);
        runs = mock(ForecastRunRepository.class);
        telemetry = mock(TelemetryRepository.class);
        live = mock(LiveEventService.class);
        evaluator = new ForecastEvaluator(points, runs, telemetry, live);
    }

    @Test
    @DisplayName("A due point is scored against the measured sample at its target")
    void should_ScorePoint_When_SampleExists() {
        ForecastPoint point = duePoint(28f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        stubSamples(sample(TARGET, 31.5f));

        int scored = evaluator.evaluateDue(TARGET.plusSeconds(5));

        assertThat(scored).isEqualTo(1);
        assertThat(point.getEvaluationStatus()).isEqualTo("SCORED");
        assertThat(point.getActualPm25()).isEqualTo(31.5f);
        assertThat(point.getError()).isEqualTo(3.5f);
        assertThat(point.getEvaluatedAt()).isNotNull();
    }

    @Test
    @DisplayName("The nearest sample wins, and an earlier one breaks a distance tie")
    void should_ChooseNearestEarlierSample_When_TwoAreInTolerance() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        stubSamples(
                sample(TARGET.plusSeconds(2), 40f),
                sample(TARGET.minusSeconds(2), 22f));

        evaluator.evaluateDue(TARGET.plusSeconds(5));

        assertThat(point.getActualPm25()).isEqualTo(22f);
    }

    @Test
    @DisplayName("A sample outside the tolerance window is not used to score a point")
    void should_NotScore_When_NoSampleIsCloseEnough() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        stubSamples(sample(TARGET.plusSeconds(9), 40f));

        int scored = evaluator.evaluateDue(TARGET.plusSeconds(10));

        assertThat(scored).isZero();
        assertThat(point.getEvaluationStatus()).isEqualTo("PENDING");
        assertThat(point.getError()).isNull();
    }

    @Test
    @DisplayName("A run accepted after its target is never scored as a forecast")
    void should_NotScore_When_RunWasAcceptedAfterTarget() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.plusSeconds(1));
        stubSamples(sample(TARGET, 30f));

        int scored = evaluator.evaluateDue(TARGET.plusSeconds(5));

        assertThat(scored).isZero();
        assertThat(point.getEvaluationStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("A sample without PM is not a usable target")
    void should_NotScore_When_SampleHasNoPm() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        stubSamples(sample(TARGET, null));

        assertThat(evaluator.evaluateDue(TARGET.plusSeconds(5))).isZero();
    }

    @Test
    @DisplayName("A missing target is marked missing, not scored with error zero")
    void should_MarkMissing_When_GraceWindowElapsed() {
        ForecastPoint point = duePoint(20f);
        when(points.findByEvaluationStatusAndTargetAtBeforeOrderByTargetAtAsc(
                eq("PENDING"), any(), any(Pageable.class))).thenReturn(List.of(point));
        when(runs.findAllById(any())).thenReturn(List.of(run(TARGET.minus(5, ChronoUnit.MINUTES))));

        int expired = evaluator.expireMissing(TARGET.plus(11, ChronoUnit.MINUTES));

        assertThat(expired).isEqualTo(1);
        assertThat(point.getEvaluationStatus()).isEqualTo("UNSCORED_MISSING_TARGET");
        assertThat(point.getError()).isNull();
        assertThat(point.getActualPm25()).isNull();
    }

    @Test
    @DisplayName("Scoring publishes a metrics event after commit")
    void should_PublishMetrics_When_PointScored() {
        ForecastPoint point = duePoint(28f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        stubSamples(sample(TARGET, 31.5f));

        evaluator.evaluateDue(TARGET.plusSeconds(5));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(live).publishAfterCommit(eq("dev-1"), eq("metrics"), eq(TARGET), any(), payload.capture());
        assertThat(payload.getValue())
                .isInstanceOfSatisfying(ForecastEvaluator.MetricsUpdate.class, update -> {
                    assertThat(update.horizonMinutes()).isEqualTo(5);
                    assertThat(update.actualPm25()).isEqualTo(31.5f);
                });
    }

    @Test
    @DisplayName("A saturated sample is not a measurement and cannot score a forecast")
    void should_NotScore_When_SampleIsSaturated() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        Telemetry saturated = sample(TARGET, 500f);
        saturated.setPm25Saturated(true);
        stubSamples(saturated);

        int scored = evaluator.evaluateDue(TARGET.plusSeconds(5));

        assertThat(scored).isZero();
        assertThat(point.getEvaluationStatus()).isEqualTo("PENDING");
        assertThat(point.getActualPm25()).isNull();
        assertThat(point.getError()).isNull();
    }

    @Test
    @DisplayName("A dust-fault sample is not a measurement and cannot score a forecast")
    void should_NotScore_When_SampleHasDustFault() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        Telemetry faulty = sample(TARGET, 42f);
        faulty.setDustFault(true);
        stubSamples(faulty);

        int scored = evaluator.evaluateDue(TARGET.plusSeconds(5));

        assertThat(scored).isZero();
        assertThat(point.getEvaluationStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("A healthy sample is still scored when a faulty one is nearer")
    void should_PreferUsableSample_When_FaultyOneIsCloser() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        Telemetry faulty = sample(TARGET, 90f);
        faulty.setDustFault(true);
        Telemetry healthy = sample(TARGET.plusSeconds(2), 22f);
        stubSamples(faulty, healthy);

        evaluator.evaluateDue(TARGET.plusSeconds(5));

        assertThat(point.getActualPm25()).isEqualTo(22f);
        assertThat(point.getEvaluationStatus()).isEqualTo("SCORED");
    }

    @Test
    @DisplayName("Nothing is published when no target can be scored")
    void should_NotPublish_When_NothingScored() {
        ForecastPoint point = duePoint(20f);
        stubDue(point);
        stubRun(TARGET.minus(5, ChronoUnit.MINUTES));
        when(telemetry.findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(anyString(), any(), any()))
                .thenReturn(List.of());

        evaluator.evaluateDue(TARGET.plusSeconds(5));

        verify(live, never()).publishAfterCommit(anyString(), anyString(), any(), any(), any());
    }

    private void stubDue(ForecastPoint point) {
        when(points.findByEvaluationStatusAndTargetAtBeforeOrderByTargetAtAsc(
                eq("PENDING"), any(), any(Pageable.class))).thenReturn(List.of(point));
    }

    private void stubRun(Instant acceptedAt) {
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(acceptedAt)));
        when(runs.findAllById(any())).thenReturn(List.of(run(acceptedAt)));
    }

    private void stubSamples(Telemetry... samples) {
        when(telemetry.findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(anyString(), any(), any()))
                .thenReturn(List.of(samples));
    }

    private static ForecastPoint duePoint(float predicted) {
        return ForecastPoint.builder()
                .id(1L)
                .runId(RUN_ID)
                .horizonMinutes(5)
                .targetAt(TARGET)
                .pm25(predicted)
                .evaluationStatus("PENDING")
                .build();
    }

    private static ForecastRun run(Instant acceptedAt) {
        return ForecastRun.builder()
                .id(RUN_ID)
                .deviceId("dev-1")
                .modelVersion("test-model")
                .inputCutoff(acceptedAt.minusSeconds(5))
                .issuedAt(acceptedAt)
                .acceptedAt(acceptedAt)
                .status("ACCEPTED")
                .build();
    }

    private static Telemetry sample(Instant at, Float pm25) {
        return Telemetry.builder()
                .id(Math.abs(at.getEpochSecond()))
                .deviceId("dev-1")
                .recordedAt(at)
                .pm25(pm25)
                .build();
    }
}
