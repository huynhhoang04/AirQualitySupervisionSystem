package com.aqss.backend.service;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.dto.mqtt.TelemetryMessage;
import com.aqss.backend.entity.AlertType;
import com.aqss.backend.entity.Severity;
import com.aqss.backend.repository.DeviceRepository;
import com.aqss.backend.repository.SpaceDeviceRepository;
import com.aqss.backend.repository.SpaceRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Write path for telemetry: validate → broadcast to STOMP immediately (&lt;5 ms) → buffer →
 * batch-persist 1×/second.
 */
@Service
@Slf4j
public class TelemetryIngestionService {

    private static final String INSERT_SQL = """
            INSERT INTO telemetry_data
                (recorded_at, device_id, space_id, pm25, co2, temperature, humidity,
                 wifi_rssi, uptime_sec, free_heap, min_free_heap)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (device_id, recorded_at) DO NOTHING
            """;

    private static final int MAX_DRAIN_PER_FLUSH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final AlertService alertService;
    private final DeviceRepository deviceRepository;
    private final SpaceDeviceRepository spaceDeviceRepository;
    private final SpaceRepository spaceRepository;
    private final BlockingQueue<TelemetryRow> queue;

    public TelemetryIngestionService(JdbcTemplate jdbcTemplate,
                                     SimpMessagingTemplate messagingTemplate,
                                     AlertService alertService,
                                     DeviceRepository deviceRepository,
                                     SpaceDeviceRepository spaceDeviceRepository,
                                     SpaceRepository spaceRepository,
                                     AqssProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.messagingTemplate = messagingTemplate;
        this.alertService = alertService;
        this.deviceRepository = deviceRepository;
        this.spaceDeviceRepository = spaceDeviceRepository;
        this.spaceRepository = spaceRepository;
        this.queue = new LinkedBlockingQueue<>(properties.telemetry().queueCapacity());
    }

    public void ingest(TelemetryMessage message) {
        var sensors = message.sensors();
        if (sensors.pm25() == null || sensors.co2() == null
                || sensors.temperature() == null || sensors.humidity() == null) {
            log.warn("Dropping telemetry from {}: incomplete sensor payload", message.deviceId());
            return;
        }
        if (!isWithinRejectRanges(message)) {
            return;
        }
        if (!deviceRepository.existsById(message.deviceId())) {
            log.warn("Dropping telemetry from unknown device {}", message.deviceId());
            return;
        }

        String spaceCode = resolveSpaceCode(message);
        raiseAdvisories(spaceCode, message, sensors);

        // Broadcast before persistence — the dashboard must see data in <5 ms.
        messagingTemplate.convertAndSend("/topic/devices/" + message.deviceId(), message);

        TelemetryRow row = new TelemetryRow(
                message.timestamp() != null ? Instant.ofEpochSecond(message.timestamp()) : Instant.now(),
                message.deviceId(), spaceCode,
                sensors.pm25(), sensors.co2(), sensors.temperature(), sensors.humidity(),
                message.system() == null || message.system().wifiRssi() == null
                        ? null : message.system().wifiRssi().shortValue(),
                message.system() == null || message.system().uptimeSec() == null
                        ? null : message.system().uptimeSec().intValue(),
                message.system() == null || message.system().freeHeap() == null
                        ? null : message.system().freeHeap().intValue(),
                message.system() == null || message.system().minFreeHeap() == null
                        ? null : message.system().minFreeHeap().intValue());

        if (!queue.offer(row)) {
            log.warn("Telemetry queue full ({}), dropping reading from {}", queue.size(), message.deviceId());
        }
    }

    private boolean isWithinRejectRanges(TelemetryMessage message) {
        var sensors = message.sensors();
        if (SensorRanges.pm25Saturated(sensors.pm25())) {
            alertService.raise(null, message.deviceId(), AlertType.SENSOR_SATURATED, Severity.WARNING,
                    "PM2.5 above the GP2Y1023 saturation point (>750 µg/m³): reading discarded");
        }
        if (SensorRanges.pm25Rejected(sensors.pm25())
                || SensorRanges.co2Rejected(sensors.co2())
                || SensorRanges.temperatureRejected(sensors.temperature())
                || SensorRanges.humidityRejected(sensors.humidity())) {
            log.warn("Dropping out-of-range telemetry from {}: {}", message.deviceId(), sensors);
            return false;
        }
        return true;
    }

    private void raiseAdvisories(String spaceCode, TelemetryMessage message, TelemetryMessage.Sensors sensors) {
        if (SensorRanges.co2Alert(sensors.co2())) {
            alertService.raise(null, message.deviceId(), AlertType.CO2_ALERT, Severity.WARNING,
                    "CO2 above 1000 ppm — ventilate (HEPA cannot remove CO2)");
        }
    }

    /** Uses the node-reported space code, falling back to the space_devices mapping. */
    private String resolveSpaceCode(TelemetryMessage message) {
        if (message.spaceId() != null && !message.spaceId().isBlank()) {
            return message.spaceId();
        }
        return spaceDeviceRepository.findByIdDeviceId(message.deviceId()).stream()
                .findFirst()
                .flatMap(sd -> spaceRepository.findById(sd.getId().getSpaceId()))
                .map(space -> space.getCode())
                .orElse(null);
    }

    @Scheduled(fixedDelayString = "${aqss.telemetry.batch-flush-interval-ms:1000}")
    public void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<TelemetryRow> batch = new ArrayList<>();
        queue.drainTo(batch, MAX_DRAIN_PER_FLUSH);
        persist(batch);
    }

    private void persist(List<TelemetryRow> batch) {
        try {
            jdbcTemplate.batchUpdate(INSERT_SQL, batch, batch.size(), (ps, row) -> {
                ps.setObject(1, OffsetDateTime.ofInstant(row.recordedAt(), ZoneOffset.UTC));
                ps.setString(2, row.deviceId());
                ps.setString(3, row.spaceId());
                ps.setFloat(4, row.pm25());
                ps.setFloat(5, row.co2());
                ps.setFloat(6, row.temperature());
                ps.setFloat(7, row.humidity());
                if (row.wifiRssi() == null) {
                    ps.setNull(8, java.sql.Types.SMALLINT);
                } else {
                    ps.setShort(8, row.wifiRssi());
                }
                setNullableInt(ps, 9, row.uptimeSec());
                setNullableInt(ps, 10, row.freeHeap());
                setNullableInt(ps, 11, row.minFreeHeap());
            });
            log.debug("Flushed {} telemetry rows", batch.size());
        } catch (Exception e) {
            log.error("Batch insert of {} telemetry rows failed: {}", batch.size(), e.getMessage());
        }
    }

    private static void setNullableInt(java.sql.PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    @PreDestroy
    public void flushOnShutdown() {
        List<TelemetryRow> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            log.info("Flushing {} buffered telemetry rows on shutdown", batch.size());
            persist(batch);
        }
    }

    record TelemetryRow(Instant recordedAt, String deviceId, String spaceId,
                        float pm25, float co2, float temperature, float humidity,
                        Short wifiRssi, Integer uptimeSec, Integer freeHeap, Integer minFreeHeap) {
    }
}
