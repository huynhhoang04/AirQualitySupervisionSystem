package com.aqss.backend.service;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.mqtt.MqttLink;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores telemetry with its provenance, serves charts, and triggers the control loop. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryService {

    /** Epoch seconds below this are treated as a clock that was never set. */
    private static final long MIN_PLAUSIBLE_EPOCH_SECONDS = 1_700_000_000L;

    private final TelemetryRepository repository;
    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final ControlLoop control;

    @EventListener
    @Transactional
    public void onTelemetry(MqttLink.TelemetryEvent event) {
        if (event.deviceId() == null || event.deviceId().isBlank()) {
            log.warn("Dropped telemetry without device_id");
            return;
        }
        MqttLink.Sensors sensors = event.sensors() == null
                ? new MqttLink.Sensors(null, null, null, null)
                : event.sensors();

        // io_contract §8 — reject physically impossible readings. A null field means "no reading
        // yet" and is kept as NULL; only a value present and outside the window is a failure.
        if (!inRange(sensors.pm25(), 0f, 500f)
                || !inRange(sensors.co2(), 400f, 5000f)
                || !inRange(sensors.temperature(), -10f, 60f)
                || !inRange(sensors.humidity(), 0f, 100f)) {
            log.warn("Rejected out-of-range telemetry from {}: {}", event.deviceId(), sensors);
            return;
        }

        Instant receivedAt = Instant.now();
        boolean clockPlausible = event.timestamp() != null
                && event.timestamp() > MIN_PLAUSIBLE_EPOCH_SECONDS;
        Instant deviceTimestamp = clockPlausible ? Instant.ofEpochSecond(event.timestamp()) : null;
        Instant recordedAt = deviceTimestamp != null ? deviceTimestamp : receivedAt;
        String timeQuality = deviceTimestamp != null ? "device" : "server_assigned";

        String payloadHash = payloadHash(event, sensors, recordedAt);

        // Idempotent ingestion: a QoS 1 redelivery or a replayed device buffer must not become a
        // second row or re-trigger the control loop.
        if (repository.existsByDeviceIdAndPayloadHash(event.deviceId(), payloadHash)) {
            log.debug("Duplicate telemetry for {} at {} ignored", event.deviceId(), recordedAt);
            return;
        }

        Device device = deviceService.ensure(event.deviceId(), event.spaceId());
        deviceService.rememberPairingCode(device, event.pairingCode());
        // Liveness follows the receive clock: a buffered packet delivered late must not make the
        // node look like it was seen in the past.
        device.setLastSeenAt(receivedAt);
        device.setLastTelemetryReceivedAt(receivedAt);
        devices.save(device);

        repository.save(Telemetry.builder()
                .deviceId(event.deviceId())
                .recordedAt(recordedAt)
                .receivedAt(receivedAt)
                .timeQuality(timeQuality)
                .qualityFlags(qualityFlags(event.system()))
                .pm25(sensors.pm25())
                .co2(sensors.co2())
                .temperature(sensors.temperature())
                .humidity(sensors.humidity())
                .wifiRssi(event.system() == null || event.system().wifi_rssi() == null
                        ? null : event.system().wifi_rssi().shortValue())
                .pm25Saturated(event.system() != null && Boolean.TRUE.equals(event.system().pm25_saturated()))
                .dustFault(event.system() == null ? null : event.system().dust_fault())
                .uptimeSec(event.system() == null ? null : event.system().uptime_sec())
                .freeHeap(event.system() == null ? null : event.system().free_heap())
                .minFreeHeap(event.system() == null ? null : event.system().min_free_heap())
                .payloadHash(payloadHash)
                .build());

        // The reading that just arrived is what the loop should judge, not the one from a minute ago.
        control.onReading(device);
    }

    private static Map<String, Object> qualityFlags(MqttLink.SystemInfo system) {
        Map<String, Object> flags = new LinkedHashMap<>();
        if (system == null) {
            return flags;
        }
        if (Boolean.TRUE.equals(system.pm25_saturated())) {
            flags.put("pm25_saturated", true);
        }
        if (Boolean.TRUE.equals(system.dust_fault())) {
            flags.put("dust_fault", true);
        }
        return flags;
    }

    /**
     * Canonical content hash. Two messages that carry the same measurements at the same instant are
     * the same sample; a different reading with the same timestamp is kept as a conflict.
     */
    private String payloadHash(MqttLink.TelemetryEvent event, MqttLink.Sensors sensors, Instant recordedAt) {
        String canonical = String.join("|",
                event.deviceId(),
                recordedAt.toString(),
                str(sensors.pm25()), str(sensors.co2()), str(sensors.temperature()), str(sensors.humidity()),
                event.system() == null ? "" : str(event.system().wifi_rssi()),
                event.system() == null ? "" : str(event.system().pm25_saturated()),
                event.system() == null ? "" : str(event.system().dust_fault()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Could not hash telemetry payload: {}", e.getMessage());
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean inRange(Float value, float min, float max) {
        return value == null || (value >= min && value <= max);
    }

    @Transactional(readOnly = true)
    public Telemetry latest(String deviceId) {
        return repository.findFirstByDeviceIdOrderByRecordedAtDesc(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no telemetry for " + deviceId));
    }

    /**
     * Legacy chart endpoint. It still thins by stride so old clients keep working; new screens use
     * {@code /series}, which aggregates with median/min/max instead of silently dropping spikes.
     */
    @Transactional(readOnly = true)
    public List<Telemetry> history(String deviceId, Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        List<Telemetry> rows =
                repository.findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(deviceId, start, end);
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
}
