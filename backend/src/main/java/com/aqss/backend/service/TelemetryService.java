package com.aqss.backend.service;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Prediction;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.mqtt.MqttLink;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.PredictionRepository;
import com.aqss.backend.repo.TelemetryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stores telemetry, serves it back for charts, and produces the 96-point forecast. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryService {

    private final TelemetryRepository repository;
    private final PredictionRepository predictions;
    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final AiClient ai;
    private final ObjectMapper mapper;

    @EventListener
    @Transactional
    public void onTelemetry(MqttLink.TelemetryEvent event) {
        if (event.deviceId() == null) {
            log.warn("Dropped telemetry without device_id");
            return;
        }
        MqttLink.Sensors sensors = event.sensors() == null ? new MqttLink.Sensors(null, null, null, null) : event.sensors();

        // io_contract §9 — reject physically impossible readings. A null field means "no reading
        // yet" and is kept as NULL; only a value present and outside the window is a failure.
        if (!inRange(sensors.pm25(), 0f, 500f)
                || !inRange(sensors.co2(), 400f, 5000f)
                || !inRange(sensors.temperature(), -10f, 60f)
                || !inRange(sensors.humidity(), 0f, 100f)) {
            log.warn("Rejected out-of-range telemetry from {}: {}", event.deviceId(), sensors);
            return;
        }

        Instant recordedAt = event.timestamp() != null && event.timestamp() > 1_700_000_000L
                ? Instant.ofEpochSecond(event.timestamp())
                : Instant.now();

        Device device = deviceService.ensure(event.deviceId(), event.spaceId());
        deviceService.rememberPairingCode(device, event.pairingCode());
        // Liveness follows the 5 s stream, not the once-per-connection status event.
        device.setLastSeenAt(recordedAt);
        devices.save(device);

        repository.save(Telemetry.builder()
                .deviceId(event.deviceId())
                .recordedAt(recordedAt)
                .pm25(sensors.pm25())
                .co2(sensors.co2())
                .temperature(sensors.temperature())
                .humidity(sensors.humidity())
                .wifiRssi(event.system() == null || event.system().wifi_rssi() == null
                        ? null : event.system().wifi_rssi().shortValue())
                .pm25Saturated(event.system() != null && Boolean.TRUE.equals(event.system().pm25_saturated()))
                .build());
    }

    private static boolean inRange(Float value, float min, float max) {
        return value == null || (value >= min && value <= max);
    }

    @Transactional(readOnly = true)
    public Telemetry latest(String deviceId) {
        return repository.findFirstByDeviceIdOrderByRecordedAtDesc(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no telemetry for " + deviceId));
    }

    @Transactional(readOnly = true)
    public List<Telemetry> history(String deviceId, Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        List<Telemetry> rows =
                repository.findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(deviceId, start, end);
        // 24 h at a 5 s interval is ~17 k rows. An SVG chart stays usable under ~1000 points and
        // the browser should not re-parse the full series every 30 s; thin to ~800.
        int step = Math.max(1, rows.size() / 800);
        if (step == 1) {
            return rows;
        }
        List<Telemetry> thinned = new ArrayList<>(rows.size() / step + 1);
        for (int i = 0; i < rows.size(); i += step) {
            thinned.add(rows.get(i));
        }
        return thinned;
    }

    /** Newest stored prediction, or empty when none exists or it is too old to act on. */
    @Transactional(readOnly = true)
    public java.util.Optional<Prediction> newestPrediction(String deviceId) {
        return predictions.findFirstByDeviceIdOrderByPredictedAtDesc(deviceId);
    }

    /** Chart data for the forecast: the last stored prediction, or {@code []}. */
    @Transactional(readOnly = true)
    public String storedForecast(String deviceId) {
        return newestPrediction(deviceId).map(Prediction::getPoints).orElse("[]");
    }

    /**
     * The stored forecast as typed points.
     *
     * <p>{@code points} is a text column, and a forecast can arrive from Layer 3 (real objects) or
     * be back-filled by hand (an array of JSON strings). Both are accepted here so one bad row
     * cannot black out the forecast panel or the control loop.
     */
    public List<AiClient.ForecastPoint> forecastPoints(String deviceId) {
        return newestPrediction(deviceId)
                .map(prediction -> parsePoints(deviceId, prediction.getPoints()))
                .orElse(List.of());
    }

    private List<AiClient.ForecastPoint> parsePoints(String deviceId, String json) {
        try {
            JsonNode node = mapper.readTree(json == null ? "[]" : json);
            if (!node.isArray()) {
                return List.of();
            }
            List<AiClient.ForecastPoint> points = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                JsonNode object = element;
                if (element.isTextual()) {
                    // Double-encoded: the row holds the JSON of a point as a string.
                    object = mapper.readTree(element.asText());
                }
                if (object != null && object.isObject()) {
                    points.add(mapper.treeToValue(object, AiClient.ForecastPoint.class));
                }
            }
            return points;
        } catch (Exception e) {
            log.warn("Ignoring unreadable forecast for {}: {}", deviceId, e.getMessage());
            return List.of();
        }
    }

    /** Resample the last 24 h to 96 fifteen-minute medians, ask Layer 3, keep the answer. */
    @Transactional
    public String refreshForecast(String deviceId) {
        Device device = deviceService.require(deviceId);
        if (!ai.enabled()) {
            return storedForecast(deviceId);
        }

        List<Telemetry> rows = history(deviceId, Instant.now().minus(Duration.ofHours(24)), Instant.now());
        Map<Instant, List<Float>> buckets = new TreeMap<>();
        for (Telemetry row : rows) {
            if (row.getPm25() == null) {
                continue;
            }
            long bucket = Math.floorDiv(row.getRecordedAt().getEpochSecond(), 900L) * 900L;
            buckets.computeIfAbsent(Instant.ofEpochSecond(bucket), key -> new ArrayList<>())
                    .add(row.getPm25());
        }
        List<AiClient.SeriesPoint> series = buckets.entrySet().stream()
                .map(entry -> new AiClient.SeriesPoint(entry.getKey(), median(entry.getValue())))
                .toList();
        if (series.isEmpty()) {
            return "[]";
        }

        return ai.forecast(new AiClient.ForecastRequest(device.getSpaceCode(), series))
                .filter(result -> result.forecastSequence() != null && !result.forecastSequence().isEmpty())
                .map(result -> store(deviceId, result))
                .orElseGet(() -> storedForecast(deviceId));
    }

    private String store(String deviceId, AiClient.ForecastResult result) {
        try {
            String points = mapper.writeValueAsString(result.forecastSequence());
            predictions.save(Prediction.builder()
                    .deviceId(deviceId)
                    .predictedAt(Instant.now())
                    .modelVersion(result.modelVersion())
                    .points(points)
                    .build());
            return points;
        } catch (Exception e) {
            log.warn("Could not store forecast for {}: {}", deviceId, e.getMessage());
            return "[]";
        }
    }

    private static Float median(List<Float> values) {
        List<Float> sorted = new ArrayList<>(values);
        sorted.sort(Float::compare);
        return sorted.get(sorted.size() / 2);
    }
}
