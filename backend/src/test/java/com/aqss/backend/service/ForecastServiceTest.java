package com.aqss.backend.service;

import com.aqss.backend.entity.Prediction;
import com.aqss.backend.entity.Space;
import com.aqss.backend.repository.PredictionRepository;
import com.aqss.backend.service.ai.AiClient;
import com.aqss.backend.service.ai.dto.ForecastRequest;
import com.aqss.backend.service.ai.dto.ForecastResponse;
import com.aqss.backend.dto.forecast.ForecastPointDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastServiceTest {

    private final PredictionRepository predictionRepository = mock(PredictionRepository.class);
    private final AiClient aiClient = mock(AiClient.class);
    private final TelemetryQueryService telemetryQueryService = mock(TelemetryQueryService.class);
    private final SpaceService spaceService = mock(SpaceService.class);
    private final ForecastService service = new ForecastService(predictionRepository, aiClient,
            new ObjectMapper().findAndRegisterModules(), telemetryQueryService, spaceService);
    private final Space space = Space.builder().id(1L).code("SP_201").build();

    @Test
    void shouldPersistForecast_WhenResponseIsValid() {
        ForecastRequest request = new ForecastRequest("SP_201", List.of());
        when(aiClient.isEnabled()).thenReturn(true);
        when(telemetryQueryService.forecastInput(any(), any())).thenReturn(request);
        when(aiClient.requestForecast(request)).thenReturn(Optional.of(response("SP_201", validPoints())));
        when(predictionRepository.save(any(Prediction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.run(space)).isTrue();
        verify(predictionRepository).save(any(Prediction.class));
    }

    @Test
    void shouldRejectForecast_WhenQuantilesAreUnordered() {
        List<ForecastPointDto> points = new java.util.ArrayList<>(validPoints());
        points.set(0, new ForecastPointDto(Instant.parse("2026-01-01T00:15:00Z"), 10f, 12f, 15f));
        ForecastRequest request = new ForecastRequest("SP_201", List.of());
        when(aiClient.isEnabled()).thenReturn(true);
        when(telemetryQueryService.forecastInput(any(), any())).thenReturn(request);
        when(aiClient.requestForecast(request)).thenReturn(Optional.of(response("SP_201", points)));

        assertThat(service.run(space)).isFalse();
    }

    private ForecastResponse response(String spaceId, List<ForecastPointDto> points) {
        return new ForecastResponse(spaceId, "ok", "forecast-v1", points);
    }

    private List<ForecastPointDto> validPoints() {
        Instant start = Instant.parse("2026-01-01T00:15:00Z");
        return IntStream.range(0, 96)
                .mapToObj(index -> new ForecastPointDto(start.plusSeconds(900L * index), 12f, 10f, 15f))
                .toList();
    }
}
