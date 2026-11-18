package com.aqss.backend.service;

import com.aqss.backend.model.ForecastRun;
import com.aqss.backend.repo.AiAlertRepository;
import com.aqss.backend.repo.AiJobRepository;
import com.aqss.backend.repo.AiModelRepository;
import com.aqss.backend.repo.AiWorkerRepository;
import com.aqss.backend.repo.ForecastPathRepository;
import com.aqss.backend.repo.ForecastPointRepository;
import com.aqss.backend.repo.ForecastRunRepository;
import com.aqss.backend.repo.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The upload contract for a 360-value curve: length, anchor and step are checked, not trusted. */
class ForecastPathUploadTest {

    private static final Instant ANCHOR = Instant.parse("2026-11-18T12:00:00Z");

    private ForecastPointRepository points;
    private ForecastRunRepository runs;
    private ForecastPathRepository paths;
    private AiService service;

    @BeforeEach
    void setUp() {
        points = mock(ForecastPointRepository.class);
        runs = mock(ForecastRunRepository.class);
        paths = mock(ForecastPathRepository.class);
        service = new AiService(
                mock(AiJobRepository.class),
                mock(AiModelRepository.class),
                mock(AiWorkerRepository.class),
                runs,
                points,
                paths,
                mock(AiAlertRepository.class),
                mock(TelemetryRepository.class),
                mock(LiveEventService.class));
        when(runs.findByDeviceIdAndModelVersionAndInputCutoff(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(runs.save(any(ForecastRun.class))).thenAnswer(call -> call.getArgument(0));
        when(points.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paths.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("A 360-value curve is stored with its anchor and step")
    void should_StorePath_When_LengthMatchesStep() {
        AiService.ForecastView view = service.uploadForecast(upload(values(360), 5, ANCHOR));

        assertThat(view.path()).isNotNull();
        assertThat(view.path().getValues()).hasSize(360);
        assertThat(view.path().getStepSeconds()).isEqualTo(5);
        assertThat(view.path().getAnchorAt()).isEqualTo(ANCHOR);
    }

    @Test
    @DisplayName("A truncated curve is rejected instead of being stored half-drawn")
    void should_RejectPath_When_LengthIsWrong() {
        assertThatThrownBy(() -> service.uploadForecast(upload(values(200), 5, ANCHOR)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("360 values");
    }

    @Test
    @DisplayName("A curve whose anchor differs from the input cutoff is rejected")
    void should_RejectPath_When_AnchorDiffersFromCutoff() {
        AiService.PathUpload path = new AiService.PathUpload(
                ANCHOR.plus(30, ChronoUnit.SECONDS), 25f, 5, values(360), null, null);

        assertThatThrownBy(() -> service.uploadForecast(uploadWithPath(path)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("anchor_at must equal input_cutoff");
    }

    @Test
    @DisplayName("A non-positive step is rejected")
    void should_RejectPath_When_StepIsNotPositive() {
        AiService.PathUpload path = new AiService.PathUpload(ANCHOR, 25f, 0, values(360), null, null);

        assertThatThrownBy(() -> service.uploadForecast(uploadWithPath(path)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("step_seconds");
    }

    @Test
    @DisplayName("The band must match the median length when present")
    void should_RejectBand_When_LengthIsWrong() {
        AiService.PathUpload path = new AiService.PathUpload(
                ANCHOR, 25f, 5, values(360), values(100), null);

        assertThatThrownBy(() -> service.uploadForecast(uploadWithPath(path)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("q10");
    }

    @Test
    @DisplayName("A three-point upload without a curve still works")
    void should_AcceptUpload_WithoutPath() {
        AiService.ForecastView view = service.uploadForecast(uploadWithPath(null));

        assertThat(view.path()).isNull();
        assertThat(view.points()).hasSize(3);
    }

    private static List<Float> values(int count) {
        List<Float> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(20f + i * 0.01f);
        }
        return result;
    }

    private static AiService.ForecastUpload upload(List<Float> values, int step, Instant anchor) {
        return uploadWithPath(new AiService.PathUpload(anchor, 20f, step, values, null, null));
    }

    private static AiService.ForecastUpload uploadWithPath(AiService.PathUpload path) {
        return new AiService.ForecastUpload(
                "dev-1", null, "chronos-2-test", ANCHOR, ANCHOR, null, 35f,
                null, "worker", List.of(
                        new AiService.Point(5, ANCHOR.plus(5, ChronoUnit.MINUTES), 21f),
                        new AiService.Point(15, ANCHOR.plus(15, ChronoUnit.MINUTES), 22f),
                        new AiService.Point(30, ANCHOR.plus(30, ChronoUnit.MINUTES), 24f)),
                path);
    }
}
