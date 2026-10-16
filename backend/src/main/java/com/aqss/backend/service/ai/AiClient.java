package com.aqss.backend.service.ai;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.entity.Space;
import com.aqss.backend.service.ai.dto.DosageRequest;
import com.aqss.backend.service.ai.dto.DosageResponse;
import com.aqss.backend.service.ai.dto.ForecastRequest;
import com.aqss.backend.service.ai.dto.ForecastResponse;
import com.aqss.backend.service.ai.dto.ThresholdRequest;
import com.aqss.backend.service.ai.dto.ThresholdResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client for the Layer 3 AI service (threshold / dosage / forecast).
 *
 * <p>Disabled in Phases 5–7 ({@code aqss.ai.enabled=false}): every method returns an empty
 * {@link Optional} so callers fall back to the rule-based stand-ins. This is the seam where the
 * ML/DL models are plugged in during Phases 8–9.
 */
@Component
@Slf4j
public class AiClient {

    private final AqssProperties properties;
    private final RestClient restClient;

    public AiClient(AqssProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.ai().timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.ai().timeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.ai().baseUrl())
                .requestFactory(factory)
                .build();
    }

    public boolean isEnabled() {
        return properties.ai().enabled();
    }

    public Optional<ThresholdResponse> requestThreshold(Space space) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return post("/api/v1/threshold",
                new ThresholdRequest(space.getCode(), space.getVolumeM3(), space.getPurpose(),
                        space.getRegion(), space.getVentilationEstimate(), occupancyFor(space.getPurpose())),
                ThresholdResponse.class);
    }

    public Optional<DosageResponse> requestDosage(Space space, double currentPm25, double baseThreshold,
                                                  double filterCadr) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return post("/api/v1/dosage",
                new DosageRequest(space.getCode(), space.getVolumeM3(), space.getPurpose(),
                        (float) currentPm25, (float) baseThreshold,
                        (float) properties.control().outdoorPm25Estimate(), (float) filterCadr),
                DosageResponse.class);
    }

    public Optional<ForecastResponse> requestForecast(ForecastRequest request) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return post("/api/v1/forecast", request, ForecastResponse.class);
    }

    private <T> Optional<T> post(String path, Object body, Class<T> responseType) {
        try {
            return Optional.ofNullable(restClient.post().uri(path).body(body).retrieve().body(responseType));
        } catch (Exception e) {
            log.warn("AI call {} failed ({}): falling back to rule-based logic", path, e.getMessage());
            return Optional.empty();
        }
    }

    private static int occupancyFor(String purpose) {
        return switch (purpose == null ? "" : purpose.toLowerCase()) {
            case "bedroom", "dorm" -> 1;
            case "classroom" -> 30;
            case "office" -> 10;
            default -> 5;
        };
    }
}
