package com.aqss.backend.service;

import com.aqss.backend.model.ActuatorHistory;
import com.aqss.backend.model.AiDatasetSnapshot;
import com.aqss.backend.model.Device;
import com.aqss.backend.model.RoomEvent;
import com.aqss.backend.model.Space;
import com.aqss.backend.model.SpaceProfileVersion;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.repo.ActuatorHistoryRepository;
import com.aqss.backend.repo.AiDatasetSnapshotRepository;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.RoomEventRepository;
import com.aqss.backend.repo.SpaceProfileVersionRepository;
import com.aqss.backend.repo.SpaceRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw data export for the AI worker.
 *
 * <p>A snapshot first freezes the membership of a window (an ordered list of row ids) inside a
 * repeatable-read transaction, then paginated reads serve slices of that list. New telemetry that
 * arrives while the worker is paging cannot insert itself between pages, and a row committed late
 * with an earlier timestamp cannot appear halfway through.
 */
@Service
@RequiredArgsConstructor
public class DatasetSnapshotService {

    private static final int DEFAULT_PAGE = 2_000;
    private static final int MAX_PAGE = 5_000;
    private static final Duration SNAPSHOT_TTL = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final AiDatasetSnapshotRepository snapshots;
    private final TelemetryRepository telemetry;
    private final ActuatorHistoryRepository actuatorHistory;
    private final RoomEventRepository roomEvents;
    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final SpaceProfileVersionRepository profiles;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SnapshotSummary create(String deviceId, Instant from, Instant to) {
        Device device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required, from < to");
        }

        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);

        List<Long> telemetryIds = jdbc.queryForList("""
                SELECT id FROM telemetry
                WHERE device_id = ? AND recorded_at >= ? AND recorded_at < ?
                ORDER BY recorded_at, id
                """, Long.class, deviceId, start, end);
        List<Long> actuatorIds = jdbc.queryForList("""
                SELECT id FROM actuator_history
                WHERE device_id = ? AND received_at >= ? AND received_at < ?
                ORDER BY received_at, id
                """, Long.class, deviceId, start, end);
        List<UUID> eventIds = jdbc.query("""
                SELECT id FROM room_events
                WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at, id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), deviceId, start, end);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("ordering", "recorded_at,id");
        manifest.put("telemetry_ids", telemetryIds);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("actuator_ids", actuatorIds);
        context.put("event_ids", eventIds.stream().map(UUID::toString).toList());
        context.put("space_code", device.getSpaceCode());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("telemetry", telemetryIds.size());
        counts.put("actuator", actuatorIds.size());
        counts.put("events", eventIds.size());

        Instant now = Instant.now();
        AiDatasetSnapshot snapshot = AiDatasetSnapshot.builder()
                .id(UUID.randomUUID())
                .deviceId(deviceId)
                .fromAt(from)
                .toAt(to)
                .manifest(manifest)
                .contextManifest(context)
                .counts(counts)
                .snapshotHash(hash(deviceId, from, to, telemetryIds))
                .status("READY")
                .createdAt(now)
                .expiresAt(now.plus(SNAPSHOT_TTL))
                .build();
        snapshots.save(snapshot);
        return summary(snapshot);
    }

    @Transactional(readOnly = true)
    public TelemetryPage telemetry(String snapshotId, String cursor, int limit) {
        AiDatasetSnapshot snapshot = require(snapshotId);
        List<Long> ids = manifestIds(snapshot, "telemetry_ids");
        Page<Long> page = page(ids, cursor, limit);
        List<Telemetry> rows = page.items().isEmpty()
                ? List.of()
                : telemetry.findByIdInOrderByRecordedAtAscIdAsc(page.items());
        return new TelemetryPage(summary(snapshot), rows, page.nextCursor(), ids.size());
    }

    @Transactional(readOnly = true)
    public ActuatorPage actuator(String snapshotId, String cursor, int limit) {
        AiDatasetSnapshot snapshot = require(snapshotId);
        List<Long> ids = contextLongIds(snapshot, "actuator_ids");
        Page<Long> page = page(ids, cursor, limit);
        List<ActuatorHistory> rows = page.items().isEmpty()
                ? List.of()
                : actuatorHistory.findByIdInOrderByReceivedAtAscIdAsc(page.items());
        return new ActuatorPage(summary(snapshot), rows, page.nextCursor(), ids.size());
    }

    @Transactional(readOnly = true)
    public EventPage events(String snapshotId, String cursor, int limit) {
        AiDatasetSnapshot snapshot = require(snapshotId);
        List<String> raw = contextIds(snapshot, "event_ids");
        List<UUID> ids = raw.stream().map(UUID::fromString).toList();
        Page<UUID> page = page(ids, cursor, limit);
        List<RoomEvent> rows = page.items().isEmpty()
                ? List.of()
                : finalizeRows(roomEvents.findByIdInOrderByOccurredAtAscIdAsc(page.items()), page.items());
        return new EventPage(summary(snapshot), rows, page.nextCursor(), ids.size());
    }

    /** Device + the room profile version in force, for the worker's metadata. */
    @Transactional(readOnly = true)
    public Context context(String deviceId) {
        Device device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
        Space space = device.getSpaceCode() == null ? null
                : spaces.findById(device.getSpaceCode()).orElse(null);
        List<SpaceProfileVersion> versions = space == null
                ? List.of() : profiles.findBySpaceCodeOrderByVersionDesc(space.getCode());
        return new Context(device, space, versions);
    }

    /**
     * Recent raw rows for inference. Not a snapshot: the worker needs the newest context, and the
     * window is bounded and small enough that a stable membership is not the point.
     */
    @Transactional(readOnly = true)
    public List<Telemetry> recent(String deviceId, int minutes) {
        devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(Math.max(1, Math.min(minutes, 240))));
        return telemetry.findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(deviceId, from, to);
    }

    /** Recent actuator reports for the inference context (fan state over the last 90 minutes). */
    @Transactional(readOnly = true)
    public List<ActuatorHistory> recentActuator(String deviceId, int minutes) {
        devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(Math.max(1, Math.min(minutes, 240))));
        return actuatorHistory.findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtAscIdAsc(deviceId, from, to);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AiDatasetSnapshot require(String snapshotId) {
        UUID id;
        try {
            id = UUID.fromString(snapshotId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid snapshot id");
        }
        AiDatasetSnapshot snapshot = snapshots.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot not found"));
        if (snapshot.getExpiresAt() != null && snapshot.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "snapshot expired");
        }
        return snapshot;
    }

    private static SnapshotSummary summary(AiDatasetSnapshot snapshot) {
        return new SnapshotSummary(snapshot.getId().toString(), snapshot.getDeviceId(),
                snapshot.getFromAt(), snapshot.getToAt(), snapshot.getSnapshotHash(),
                snapshot.getStatus(), snapshot.getCreatedAt(), snapshot.getExpiresAt(),
                snapshot.getCounts());
    }

    @SuppressWarnings("unchecked")
    private static List<Long> manifestIds(AiDatasetSnapshot snapshot, String key) {
        Object raw = snapshot.getManifest() == null ? null : snapshot.getManifest().get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(value -> ((Number) value).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> contextIds(AiDatasetSnapshot snapshot, String key) {
        Object raw = snapshot.getContextManifest() == null ? null : snapshot.getContextManifest().get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> contextLongIds(AiDatasetSnapshot snapshot, String key) {
        Object raw = snapshot.getContextManifest() == null ? null : snapshot.getContextManifest().get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(value -> ((Number) value).longValue()).toList();
    }

    private static <T> Page<T> page(List<T> ids, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? DEFAULT_PAGE : requestedLimit, MAX_PAGE));
        int offset = decodeCursor(cursor);
        if (offset >= ids.size()) {
            return new Page<>(List.of(), null);
        }
        int end = Math.min(ids.size(), offset + limit);
        List<T> slice = new ArrayList<>(ids.subList(offset, end));
        String next = end < ids.size() ? encodeCursor(end) : null;
        return new Page<>(slice, next);
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
        }
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    /** The repository returns rows ordered by its own key; keep the manifest order. */
    private static List<RoomEvent> finalizeRows(List<RoomEvent> rows, List<UUID> order) {
        Map<UUID, RoomEvent> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        return order.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private static String hash(String deviceId, Instant from, Instant to, List<Long> ids) {
        try {
            String canonical = deviceId + "|" + from + "|" + to + "|" + ids;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private record Page<T>(List<T> items, String nextCursor) {
    }

    public record SnapshotSummary(String id, String deviceId, Instant from, Instant to, String snapshotHash,
                                  String status, Instant createdAt, Instant expiresAt,
                                  Map<String, Object> counts) {
    }

    public record TelemetryPage(SnapshotSummary snapshot, List<Telemetry> items, String nextCursor,
                                int total) {
    }

    public record ActuatorPage(SnapshotSummary snapshot, List<ActuatorHistory> items, String nextCursor,
                               int total) {
    }

    public record EventPage(SnapshotSummary snapshot, List<RoomEvent> items, String nextCursor, int total) {
    }

    public record Context(Device device, Space space, List<SpaceProfileVersion> profileVersions) {
    }
}
