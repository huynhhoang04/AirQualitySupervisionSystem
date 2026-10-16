package com.aqss.backend.service;

import com.aqss.backend.dto.forecast.ForecastDto;
import com.aqss.backend.dto.forecast.ForecastPointDto;
import com.aqss.backend.entity.Prediction;
import com.aqss.backend.entity.Space;
import com.aqss.backend.repository.PredictionRepository;
import com.aqss.backend.service.ai.AiClient;
import com.aqss.backend.service.ai.dto.ForecastResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads cached forecasts. Producing one (24 h resample → {@code /api/v1/forecast} → persist) is a
 * Phase 8 concern; with the AI service disabled there is simply no forecast and the early-warning
 * branch of the control loop stays inert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForecastService {

    private final PredictionRepository predictionRepository;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final TelemetryQueryService telemetryQueryService;
    private final SpaceService spaceService;

    @Transactional(readOnly = true)
    public Optional<Forecast> latestFor(Space space) {
        return predictionRepository.findFirstBySpaceIdOrderByPredictedAtDesc(space.getId())
                .map(this::toForecast);
    }

    /** Returns an empty forecast (200) rather than 404 so the dashboard renders gracefully. */
    @Transactional(readOnly = true)
    public ForecastDto latestDto(Space space) {
        String spaceId = space.getCode();
        return latestFor(space)
                .map(f -> new ForecastDto(spaceId, f.predictedAt(), f.modelVersion(), f.points()))
                .orElseGet(() -> new ForecastDto(spaceId, null, "unavailable", List.of()));
    }

    @Transactional
    public boolean run(Space space) {
        if (!aiClient.isEnabled()) {
            return false;
        }
        var input = telemetryQueryService.forecastInput(space.getCode(), Instant.now());
        if (input == null) {
            log.info("Skipping forecast for space {} because 24-hour telemetry is incomplete or stale", space.getCode());
            return false;
        }
        Optional<ForecastResponse> response = aiClient.requestForecast(input);
        if (response.isEmpty() || !validResponse(space, response.get())) {
            log.warn("Forecast inference unavailable or invalid for space {}", space.getCode());
            return false;
        }
        try {
            ForecastResponse forecast = response.get();
            Prediction prediction = Prediction.builder()
                    .spaceId(space.getId())
                    .predictedAt(Instant.now())
                    .forecastData(objectMapper.writeValueAsString(forecast.forecastSequence()))
                    .modelVersion(forecast.modelVersion())
                    .build();
            predictionRepository.save(prediction);
            return true;
        } catch (Exception e) {
            log.warn("Could not persist forecast for space {}: {}", space.getCode(), e.getMessage());
            return false;
        }
    }

    @Scheduled(cron = "${aqss.control.forecast-cron:0 0 * * * *}")
    public void runScheduled() {
        if (!aiClient.isEnabled()) {
            return;
        }
        for (Space space : spaceService.findAll()) {
            try {
                run(space);
            } catch (Exception e) {
                log.error("Forecast cycle failed for space {}: {}", space.getCode(), e.getMessage(), e);
            }
        }
    }

    private boolean validResponse(Space space, ForecastResponse response) {
        return space.getCode().equals(response.spaceId())
                && "ok".equals(response.status())
                && response.modelVersion() != null
                && !response.modelVersion().isBlank()
                && response.forecastSequence() != null
                && response.forecastSequence().size() == 96
                && response.forecastSequence().stream().allMatch(point -> point.targetTime() != null
                        && point.pm25Q10() != null && point.pm25Q50() != null && point.pm25Q90() != null
                        && point.pm25Q10() >= 0 && point.pm25Q10() <= point.pm25Q50()
                        && point.pm25Q50() <= point.pm25Q90() && point.pm25Q90() <= 500)
                && response.forecastSequence().stream().allMatch(point -> point.targetTime()
                        .equals(response.forecastSequence().get(0).targetTime()
                                .plusSeconds(900L * response.forecastSequence().indexOf(point))));
    }

    private Forecast toForecast(Prediction prediction) {
        List<ForecastPointDto> points = parse(prediction.getForecastData());
        return new Forecast(prediction.getPredictedAt(), prediction.getModelVersion(), points);
    }

    private List<ForecastPointDto> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ForecastPointDto>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse stored forecast: {}", e.getMessage());
            return List.of();
        }
    }
}
