package com.aqss.backend.service;

import com.aqss.backend.config.AqssSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The backend is the caller: three requests to Layer 3 (threshold, dosage, forecast) and the three
 * answers come back on the same call. Payloads follow io_contract.md §8.
 *
 * <p>The client is built from Spring's managed {@link RestClient.Builder} on purpose. A static
 * {@code RestClient.builder()} would install its own Jackson converter, which does not apply
 * {@code spring.jackson.property-naming-strategy=SNAKE_CASE} — every request would go out camelCase
 * and hit a 422, and every snake_case response field would decode to null.
 */
@Component
@Slf4j
public class AiClient {

    private final AqssSettings.Ai settings;
    private final RestClient http;

    public AiClient(AqssSettings aqssSettings, RestClient.Builder builder) {
        this.settings = aqssSettings.ai();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.timeoutMs());
        factory.setReadTimeout(settings.timeoutMs());
        this.http = builder.clone().baseUrl(settings.baseUrl()).requestFactory(factory).build();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public Optional<ThresholdResult> threshold(ThresholdRequest request) {
        return post("/api/v1/threshold", request, ThresholdResult.class);
    }

    public Optional<DosageResult> dosage(DosageRequest request) {
        return post("/api/v1/dosage", request, DosageResult.class);
    }

    public Optional<ForecastResult> forecast(ForecastRequest request) {
        return post("/api/v1/forecast", request, ForecastResult.class);
    }

    private <T> Optional<T> post(String path, Object body, Class<T> type) {
        if (!settings.enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(http.post().uri(path).body(body).retrieve().body(type));
        } catch (Exception e) {
            log.warn("Layer 3 call {} failed: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    // ── §8 ML#1 ──────────────────────────────────────────────────────────
    public record ThresholdRequest(String spaceId, Float volumeM3, String purpose, String region,
                                   String ventilationEstimate, Integer occupancyTypical) {
    }

    public record ThresholdResult(String spaceId, Float baseThresholdPm25, Float uncertaintyLower,
                                  Float uncertaintyUpper, String modelVersion) {
    }

    // ── §9 ML#2 ──────────────────────────────────────────────────────────
    public record DosageRequest(String spaceId, Float volumeM3, String purpose, Float currentPm25,
                                Float baseThreshold, Float outdoorPm25Estimate, Float filterCadr) {
    }

    public record DosageResult(String spaceId, Integer filterLevel, Integer runDurationMinutes,
                               String modelVersion) {
    }

    // ── §10 DL forecast ──────────────────────────────────────────────────
    public record SeriesPoint(Instant timestamp, Float pm25) {
    }

    public record ForecastRequest(String spaceId, List<SeriesPoint> series) {
    }

    public record ForecastPoint(Instant targetTime, Float pm25Q10, Float pm25Q50, Float pm25Q90) {
    }

    public record ForecastResult(String spaceId, String status, String modelVersion,
                                 List<ForecastPoint> forecastSequence) {
    }
}
