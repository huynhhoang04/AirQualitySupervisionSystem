package com.aqss.backend.service;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Space;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Bucketed reads for the dashboard. Aggregation happens in SQL so a 24 h window does not become
 * 17 k rows in the browser, and min/max travel with the median so a spike is never silently
 * dropped the way stride-thinning drops it.
 */
@Service
@RequiredArgsConstructor
public class TelemetryAnalyticsService {

    private static final int MAX_BUCKETS = 2_000;
    private static final int MAX_SAMPLES = 5_000;

    private final JdbcTemplate jdbc;
    private final DeviceRepository devices;
    private final SpaceRepository spaces;

    @Transactional(readOnly = true)
    public Series series(String deviceId, Instant from, Instant to, String bucket) {
        Device device = requireDevice(deviceId);
        Window window = window(from, to);
        int bucketSeconds = bucketSeconds(bucket);
        if (Duration.between(window.from(), window.to()).getSeconds() / bucketSeconds > MAX_BUCKETS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "range needs a larger bucket (max " + MAX_BUCKETS + " buckets per request)");
        }

        int expected = expectedPerBucket(device, bucketSeconds);
        List<SeriesBucket> items = jdbc.query("""
                        SELECT date_bin(?::interval, recorded_at, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS bucket_start,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY pm25) AS median_pm,
                               min(pm25) AS min_pm,
                               max(pm25) AS max_pm,
                               count(pm25) AS n
                        FROM telemetry
                        WHERE device_id = ? AND recorded_at >= ? AND recorded_at < ? AND pm25 IS NOT NULL
                        GROUP BY 1
                        ORDER BY 1
                        """,
                (rs, rowNum) -> {
                    int count = rs.getInt("n");
                    return new SeriesBucket(
                            rs.getTimestamp("bucket_start").toInstant(),
                            floatOrNull(rs, "median_pm"),
                            floatOrNull(rs, "min_pm"),
                            floatOrNull(rs, "max_pm"),
                            count,
                            expected,
                            expected == 0 ? 0.0 : Math.min(1.0, (double) count / expected));
                },
                bucketSeconds + " seconds",
                deviceId,
                Timestamp.from(window.from()),
                Timestamp.from(window.to()));

        return new Series(deviceId, window.from(), window.to(), bucket, bucketSeconds, items);
    }

    @Transactional(readOnly = true)
    public Summary summary(String deviceId, Instant from, Instant to, Float threshold) {
        Device device = requireDevice(deviceId);
        Window window = window(from, to);
        float effectiveThreshold = threshold != null ? threshold : thresholdOf(device);

        Row row = jdbc.queryForObject("""
                        SELECT count(*) AS total,
                               count(pm25) AS pm_count,
                               avg(pm25) AS avg_pm,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY pm25) AS median_pm,
                               max(pm25) AS max_pm
                        FROM telemetry
                        WHERE device_id = ? AND recorded_at >= ? AND recorded_at < ?
                        """,
                (rs, rowNum) -> new Row(
                        rs.getLong("total"),
                        rs.getLong("pm_count"),
                        doubleOrNull(rs, "avg_pm"),
                        doubleOrNull(rs, "median_pm"),
                        doubleOrNull(rs, "max_pm")),
                deviceId, Timestamp.from(window.from()), Timestamp.from(window.to()));

        int expected = expectedTotal(device, window);
        long minutesWithData = jdbc.queryForObject("""
                        SELECT count(DISTINCT date_trunc('minute', recorded_at))
                        FROM telemetry WHERE device_id = ? AND recorded_at >= ? AND recorded_at < ?
                        """,
                Long.class, deviceId, Timestamp.from(window.from()), Timestamp.from(window.to()));
        long minutesAbove = jdbc.queryForObject("""
                        SELECT count(DISTINCT date_trunc('minute', recorded_at))
                        FROM telemetry
                        WHERE device_id = ? AND recorded_at >= ? AND recorded_at < ? AND pm25 >= ?
                        """,
                Long.class, deviceId, Timestamp.from(window.from()), Timestamp.from(window.to()),
                effectiveThreshold);
        long totalMinutes = Math.max(1, Duration.between(window.from(), window.to()).toMinutes());

        return new Summary(
                deviceId,
                window.from(),
                window.to(),
                row.total(),
                row.pmCount(),
                row.avgPm(),
                row.medianPm(),
                row.maxPm(),
                expected == 0 ? 0.0 : Math.min(1.0, (double) row.pmCount() / expected),
                minutesAbove,
                Math.max(0, totalMinutes - minutesWithData),
                totalMinutes,
                effectiveThreshold);
    }

    @Transactional(readOnly = true)
    public SamplePage samples(String deviceId, Instant from, Instant to, int limit, String cursor) {
        requireDevice(deviceId);
        Window window = window(from, to);
        int pageSize = Math.max(1, Math.min(limit, MAX_SAMPLES));
        Cursor start = Cursor.decode(cursor);

        List<Sample> rows;
        if (start == null) {
            rows = jdbc.query(SAMPLE_SQL + " AND recorded_at >= ? AND recorded_at < ? ORDER BY recorded_at, id LIMIT ?",
                    SAMPLE_MAPPER,
                    deviceId, Timestamp.from(window.from()), Timestamp.from(window.to()), pageSize + 1);
        } else {
            rows = jdbc.query(SAMPLE_SQL + """
                             AND recorded_at >= ? AND recorded_at < ?
                               AND (recorded_at, id) > (?, ?)
                             ORDER BY recorded_at, id LIMIT ?
                            """,
                    SAMPLE_MAPPER,
                    deviceId, Timestamp.from(window.from()), Timestamp.from(window.to()),
                    Timestamp.from(start.recordedAt()), start.id(), pageSize + 1);
        }

        boolean hasMore = rows.size() > pageSize;
        List<Sample> items = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore
                ? Cursor.encode(items.get(items.size() - 1).recordedAt(), items.get(items.size() - 1).id())
                : null;
        return new SamplePage(deviceId, window.from(), window.to(), items, next);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Device requireDevice(String deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
    }

    private float thresholdOf(Device device) {
        if (device.getSpaceCode() == null) {
            return 35f;
        }
        return spaces.findById(device.getSpaceCode()).map(Space::getThresholdPm25).orElse(35f);
    }

    private static Window window(Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        return new Window(start, end);
    }

    private static int bucketSeconds(String bucket) {
        return switch (bucket == null ? "5m" : bucket) {
            case "1m" -> 60;
            case "5m" -> 300;
            case "15m" -> 900;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bucket must be one of 1m, 5m, 15m");
        };
    }

    private static int expectedPerBucket(Device device, int bucketSeconds) {
        int intervalMs = device.getTelemetryIntervalMs() == null ? 5000 : device.getTelemetryIntervalMs();
        return Math.max(1, (int) Math.round(bucketSeconds * 1000.0 / intervalMs));
    }

    private static int expectedTotal(Device device, Window window) {
        int intervalMs = device.getTelemetryIntervalMs() == null ? 5000 : device.getTelemetryIntervalMs();
        long seconds = Math.max(1, Duration.between(window.from(), window.to()).getSeconds());
        return (int) Math.min(Integer.MAX_VALUE, Math.round(seconds * 1000.0 / intervalMs));
    }

    private static Float floatOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        float value = rs.getFloat(column);
        return rs.wasNull() ? null : value;
    }

    private static Double doubleOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static final String SAMPLE_SQL = """
            SELECT id, device_id, recorded_at, received_at, pm25, co2, temperature, humidity,
                   wifi_rssi, pm25_saturated, dust_fault, time_quality
            FROM telemetry
            WHERE device_id = ?
            """;

    private static final org.springframework.jdbc.core.RowMapper<Sample> SAMPLE_MAPPER = (rs, rowNum) -> new Sample(
            rs.getLong("id"),
            rs.getString("device_id"),
            rs.getTimestamp("recorded_at") == null ? null : rs.getTimestamp("recorded_at").toInstant(),
            rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
            floatOrNull(rs, "pm25"),
            floatOrNull(rs, "co2"),
            floatOrNull(rs, "temperature"),
            floatOrNull(rs, "humidity"),
            rs.getObject("wifi_rssi") == null ? null : rs.getShort("wifi_rssi"),
            rs.getBoolean("pm25_saturated"),
            rs.getObject("dust_fault") == null ? null : rs.getBoolean("dust_fault"),
            rs.getString("time_quality"));

    private record Window(Instant from, Instant to) {
    }

    private record Row(long total, long pmCount, Double avgPm, Double medianPm, Double maxPm) {
    }

    private record Cursor(Instant recordedAt, long id) {
        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 2);
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
            }
        }

        static String encode(Instant recordedAt, long id) {
            String raw = recordedAt.toEpochMilli() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── response shapes ──────────────────────────────────────────────────────

    public record SeriesBucket(Instant bucketStart, Float median, Float min, Float max,
                               long count, int expectedCount, double coverage) {
    }

    public record Series(String deviceId, Instant from, Instant to, String bucket,
                         int bucketSeconds, List<SeriesBucket> items) {
    }

    public record Summary(String deviceId, Instant from, Instant to, long totalRows, long pmCount,
                          Double avgPm25, Double medianPm25, Double maxPm25, double coverage,
                          long minutesAboveThreshold, long unknownMinutes, long totalMinutes,
                          float thresholdPm25) {
    }

    public record Sample(long id, String deviceId, Instant recordedAt, Instant receivedAt,
                         Float pm25, Float co2, Float temperature, Float humidity, Short wifiRssi,
                         boolean pm25Saturated, Boolean dustFault, String timeQuality) {
    }

    public record SamplePage(String deviceId, Instant from, Instant to, List<Sample> items,
                             String nextCursor) {
    }
}
