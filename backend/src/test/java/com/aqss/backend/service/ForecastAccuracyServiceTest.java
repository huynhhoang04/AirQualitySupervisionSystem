package com.aqss.backend.service;

import com.aqss.backend.model.ForecastRun;
import com.aqss.backend.repo.ForecastPointRepository;
import com.aqss.backend.repo.ForecastRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastAccuracyServiceTest {

    private ForecastPointRepository points;
    private ForecastRunRepository runs;
    private ForecastAccuracyService service;

    @BeforeEach
    void setUp() {
        points = mock(ForecastPointRepository.class);
        runs = mock(ForecastRunRepository.class);
        service = new ForecastAccuracyService(points, runs);
    }

    @Test
    @DisplayName("MAE and the within-tolerance rate come from the stored pairs")
    void should_ComputeIndicators_FromScoredPairs() {
        stubModel("model-a");
        stubAggregate(new Aggregate(5, 4L, 2.5, 3L, 1L, 2L, 0L));

        ForecastAccuracyService.Accuracy result = service.accuracy("dev-1", 5, 6, null);

        assertThat(result.matched()).isEqualTo(4);
        assertThat(result.maeUgM3()).isEqualTo(2.5);
        assertThat(result.withinTolerance()).isEqualTo(3);
        assertThat(result.withinTolerancePct()).isEqualTo(75.0);
        assertThat(result.pending()).isEqualTo(1);
        assertThat(result.missing()).isEqualTo(2);
        assertThat(result.modelVersion()).isEqualTo("model-a");
        assertThat(result.toleranceUgM3()).isEqualTo(5f);
    }

    @Test
    @DisplayName("No matched pair reports null, never 100 %")
    void should_ReportNull_WhenNothingMatched() {
        stubModel("model-a");
        stubAggregate(new Aggregate(5, 0L, null, 0L, 7L, 0L, 0L));

        ForecastAccuracyService.Accuracy result = service.accuracy("dev-1", 5, 6, null);

        assertThat(result.matched()).isZero();
        assertThat(result.maeUgM3()).isNull();
        assertThat(result.withinTolerancePct()).isNull();
    }

    @Test
    @DisplayName("A horizon with no rows at all is reported as zero matched")
    void should_ReportZero_WhenCohortEmpty() {
        stubModel("model-a");
        when(points.aggregate(anyString(), anyString(), any(), any(), anyFloat(), anyFloat()))
                .thenReturn(List.of());

        ForecastAccuracyService.Accuracy result = service.accuracy("dev-1", 15, 6, null);

        assertThat(result.matched()).isZero();
        assertThat(result.maeUgM3()).isNull();
        assertThat(result.horizons()).hasSize(3);
    }

    @Test
    @DisplayName("Every 5/15/30 row is returned for the selector")
    void should_ReturnAllHorizons() {
        stubModel("model-a");
        stubAggregate(
                new Aggregate(5, 10L, 2.0, 8L, 0L, 0L, 0L),
                new Aggregate(15, 6L, 4.0, 3L, 1L, 0L, 1L),
                new Aggregate(30, 2L, 7.5, 1L, 0L, 0L, 0L));

        ForecastAccuracyService.Accuracy result = service.accuracy("dev-1", 5, 6, null);

        assertThat(result.horizons())
                .extracting(ForecastAccuracyService.HorizonSummary::horizonMinutes, 
                        ForecastAccuracyService.HorizonSummary::matched)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(5, 10L),
                        org.assertj.core.groups.Tuple.tuple(15, 6L),
                        org.assertj.core.groups.Tuple.tuple(30, 2L));
    }

    @Test
    @DisplayName("An unsupported horizon is rejected instead of silently ignored")
    void should_RejectUnsupportedHorizon() {
        assertThatThrownBy(() -> service.accuracy("dev-1", 7, 6, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("horizon must be 5, 15 or 30");
    }

    @Test
    @DisplayName("Without a forecast there is no cohort to report")
    void should_Reject_WhenDeviceHasNoForecast() {
        when(runs.findFirstByDeviceIdOrderByIssuedAtDesc("dev-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accuracy("dev-1", 5, 6, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no forecast");
    }

    @Test
    @DisplayName("The relative percentage uses its own denominator, not the absolute one")
    void should_ComputeRelativePercentage_OnItsOwnBase() {
        stubModel("model-a");
        // 4 scored pairs, but only 3 have an actual value above zero (the only base a relative
        // error is defined for); 2 of those 3 are within 30 %.
        stubAggregate(new Aggregate(30, 4L, 2.5, 3L, 0L, 0L, 0L, 3L, 2L));

        ForecastAccuracyService.Accuracy result = service.accuracy("dev-1", 30, 6, null);

        assertThat(result.matched()).isEqualTo(4);
        assertThat(result.relativeBase()).isEqualTo(3);
        assertThat(result.withinRelative()).isEqualTo(2);
        assertThat(result.withinRelativePct()).isEqualTo(66.67);
        assertThat(result.relativeTolerance()).isEqualTo(0.30f);
        // The absolute indicator keeps its own denominator and is not overwritten by the relative one.
        assertThat(result.withinTolerancePct()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("No positive actual value means no relative percentage, never zero")
    void should_ReportNullRelative_WhenBaseIsZero() {
        stubModel("model-a");
        stubAggregate(new Aggregate(30, 2L, 3.0, 1L, 0L, 0L, 0L, 0L, 0L));

        ForecastAccuracyService.Accuracy result = service.accuracy("dev-1", 30, 6, null);

        assertThat(result.withinRelativePct()).isNull();
        assertThat(result.withinTolerancePct()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("The drawer passes the requested horizon into the query")
    void should_PassHorizonIntoQuery() {
        stubModel("model-a");
        when(points.recentScored(anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(
                        new ScoredForecastPoint(Instant.now(), 30, 20f, 22f, 2f,
                                Instant.now(), "model-a", Instant.now())));

        List<ScoredForecastPoint> rows = service.recent("dev-1", 30, 6, null, 50);

        // The filter belongs in SQL. Applying it to a page of mixed rows kept only the few 30-minute
        // rows that happened to be in the first page — the drawer showed 2 rows for a cohort of 346.
        verify(points).recentScored(eq("dev-1"), eq("model-a"), any(), eq(30), any(Pageable.class));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).horizonMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("Horizon 0 means every horizon, passed as null")
    void should_PassNullHorizon_When_ZeroRequested() {
        stubModel("model-a");
        when(points.recentScored(anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        service.recent("dev-1", 0, 6, null, 50);

        verify(points).recentScored(eq("dev-1"), eq("model-a"), any(), isNull(), any(Pageable.class));
    }

    private void stubModel(String version) {
        when(runs.findFirstByDeviceIdOrderByIssuedAtDesc("dev-1")).thenReturn(Optional.of(
                ForecastRun.builder()
                        .id(java.util.UUID.randomUUID())
                        .deviceId("dev-1")
                        .modelVersion(version)
                        .inputCutoff(Instant.now())
                        .issuedAt(Instant.now())
                        .acceptedAt(Instant.now())
                        .status("ACCEPTED")
                        .build()));
    }

    private void stubAggregate(ForecastPointRepository.HorizonAggregate... rows) {
        when(points.aggregate(eq("dev-1"), anyString(), any(), any(), anyFloat(), anyFloat()))
                .thenReturn(List.of(rows));
    }

    /** Fixtures default the relative counts to the same shape as the absolute ones. */
    private record Aggregate(Integer horizon, Long matched, Double mae, Long within, Long pending,
                             Long missing, Long late, Long relativeBase,
                             Long withinRelative) implements ForecastPointRepository.HorizonAggregate {

        Aggregate(Integer horizon, Long matched, Double mae, Long within, Long pending,
                  Long missing, Long late) {
            this(horizon, matched, mae, within, pending, missing, late, matched, within);
        }

        @Override
        public Long getRelativeBase() {
            return relativeBase;
        }

        @Override
        public Long getWithinRelative() {
            return withinRelative;
        }

        @Override
        public Integer getHorizonMinutes() {
            return horizon;
        }

        @Override
        public Long getMatched() {
            return matched;
        }

        @Override
        public Double getMae() {
            return mae;
        }

        @Override
        public Long getWithin() {
            return within;
        }

        @Override
        public Long getPending() {
            return pending;
        }

        @Override
        public Long getMissing() {
            return missing;
        }

        @Override
        public Long getLate() {
            return late;
        }
    }
}
