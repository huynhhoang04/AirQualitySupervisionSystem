package com.aqss.backend.service;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Prediction;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.mqtt.MqttLink;
import com.aqss.backend.repo.PredictionRepository;
import com.aqss.backend.repo.TelemetryRepository;
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
    private final DeviceService devices;
    private final AiClient ai;
    private final ObjectMapper mapper;

    @EventListener
    @Transactional
    public void onTelemetry(MqttLink.TelemetryEvent event) {
        if (event.deviceId() == null || event.sensors() == null) {
            log.warn("Dropped telemetry without device_id or sensors");
            return;
        }
        MqttLink.Sensors sensors = event.sensors();

        // io_contract §12 — reject physically impossible readings.
        if (!inRange(sensors.pm25(), 0f, 500f)
                || !inRange(sensors.co2(), 300f, 10000f)
                || !inRange(sensors.temperature(), -40f, 85f)
                || !inRange(sensors.humidity(), 0f, 100f)) {
            log.warn("Rejected out-of-range telemetry from {}: {}", event.deviceId(), sensors);
            return;
        }

        Instant recordedAt = event.timestamp() != null && event.timestamp() > 1_700_000_000L
                ? Instant.ofEpochSecond(event.timestamp())
                : Instant.now();

        devices.ensure(event.deviceId(), event.spaceId());
        repository.save(Telemetry.builder()
                .deviceId(event.deviceId())
                .recordedAt(recordedAt)
                .pm25(sensors.pm25())
                .co2(sensors.co2())
                .temperature(sensors.temperature())
                .humidity(sensors.humidity())
                .wifiRssi(event.system() == null || event.system().wifi_rssi() == null
                        ? null : event.system().wifi_rssi().shortValue())
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
        return repository.findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(deviceId, start, end);
    }

    /** Chart data for the forecast: the last stored prediction, or {@code []}. */
    @Transactional(readOnly = true)
    public String storedForecast(String deviceId) {
        return predictions.findFirstByDeviceIdOrderByPredictedAtDesc(deviceId)
                .map(Prediction::getPoints)
                .orElse("[]");
    }

    /** Resample the last 24 h to 96 fifteen-minute medians, ask Layer 3, keep the answer. */
    @Transactional
    public String refreshForecast(String deviceId) {
        Device device = devices.require(deviceId);
        if (!ai.enabled()) {
            return storedForecast(deviceId);
        }

        List<Telemetry> rows = history(deviceId, Instant.now().minus(Duration.ofHours(24)), Instant.now());
        Map<Instant, List<Float>> buckets = new TreeMap<>();
        for (Telemetry row : rows) {
            long bucket = Math.floorDiv(row.getRecordedAt().getEpochSecond(), 900L) * 900L;
            buckets.computeIfAbsent(Instant.ofEpochSecond(bucket), key -> new ArrayList<>())
                    .add(row.getPm25() == null ? 0f : row.getPm25());
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
