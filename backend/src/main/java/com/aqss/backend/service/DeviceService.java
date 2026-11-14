package com.aqss.backend.service;

import com.aqss.backend.model.ActuatorHistory;
import com.aqss.backend.model.CommandLog;
import com.aqss.backend.model.Device;
import com.aqss.backend.model.Space;
import com.aqss.backend.mqtt.MqttLink;
import com.aqss.backend.repo.ActuatorHistoryRepository;
import com.aqss.backend.repo.CommandLogRepository;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Device registry, pairing, configuration and every command sent to a node. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    /** Codes arrive from a phone keyboard; strip anything that is not a symbol A–Z / 2–9. */
    private static final Pattern CODE_NOISE = Pattern.compile("[^A-Z2-9]");

    /** A command is only "pending" for this long; after that a new AUTO decision may replace it. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);

    private static final List<String> PENDING_STATUSES = List.of("REQUESTED", "PUBLISHED");

    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final CommandLogRepository commands;
    private final ActuatorHistoryRepository actuatorHistory;
    private final MqttLink mqtt;
    private final AuditService audit;
    private final SpaceCodes spaceCodes;
    private final ThresholdService thresholds;

    @Transactional(readOnly = true)
    public List<Device> findAll(boolean includeUnpaired) {
        List<Device> all = devices.findAll();
        return includeUnpaired ? all : all.stream().filter(d -> d.getPairedAt() != null).toList();
    }

    @Transactional(readOnly = true)
    public Device require(String deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
    }

    /**
     * A node is registered the first time it is heard from — but unpaired.
     *
     * <p>The {@code space_id} in a telemetry payload comes from the node's own NVS and can be a
     * stale copy of a room the operator already removed. It is therefore never used to bind: only
     * {@link #pair} does that. Re-linking here would un-unpair a device on its next message, which
     * is exactly what "I removed it but it came back after a refresh" looked like.
     */
    @Transactional
    public Device ensure(String deviceId, String spaceCode) {
        return devices.findById(deviceId).orElseGet(() -> {
            Device created = Device.builder()
                    .deviceId(deviceId)
                    .name(deviceId)
                    .spaceCode(null)
                    .fanState("OFF")
                    .fanLevel((short) 0)
                    .fanMode("AUTO")
                    .telemetryIntervalMs(5000)
                    .dustBaselineUs(1400f)
                    .filterRuntimeS(0)
                    .offSince(Instant.now())
                    .createdAt(Instant.now())
                    .build();
            log.info("registered unpaired device {} (telemetry space hint '{}' ignored; pairing binds)",
                    deviceId, spaceCode);
            return devices.save(created);
        });
    }

    /** Every inbound message may carry the pairing code; remember the newest one. */
    @Transactional
    public void rememberPairingCode(Device device, String pairingCode) {
        if (pairingCode == null || pairingCode.isBlank()) {
            return;
        }
        if (!pairingCode.equals(device.getPairingCode())) {
            device.setPairingCode(pairingCode);
            devices.save(device);
        }
    }

    /**
     * {@code POST /devices/pair} — the only way a node becomes visible on the dashboard.
     *
     * <p>A space is created when the operator names one that does not exist yet, so the connect flow
     * never has to ask "which room code?". The name is both the room label and the device label.
     */
    @Transactional
    public Device pair(String rawCode, String spaceCode, String name, String spaceName,
                       String purpose, Float volumeM3, Integer occupancyHours) {
        String code = normalise(rawCode);
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pairing_code is required");
        }

        Device device = devices.findFirstByPairingCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no node has connected with code " + code + " yet"));

        String roomCode = spaceCode == null ? "" : spaceCode.trim();
        if (roomCode.isEmpty()) {
            // Caller named a room instead of passing a code: derive one and create it on demand.
            String label = (spaceName == null || spaceName.isBlank())
                    ? (name == null || name.isBlank() ? "Phòng" : name)
                    : spaceName;
            roomCode = spaceCodes.derive(label);
            // The template the operator picked carries the room's profile; the threshold is derived
            // from it by the same service the settings screen uses, so both paths agree.
            Space created = Space.builder()
                    .code(roomCode)
                    .name(label)
                    .purpose(purpose)
                    .volumeM3(volumeM3)
                    .occupancyHours(occupancyHours)
                    .thresholdPm25(35f)
                    .thresholdSource("manual")
                    .updatedAt(Instant.now())
                    .build();
            created.setThresholdPm25((float) thresholds.compute(created, null).thresholdPm25());
            created.setThresholdSource("profile");
            spaces.save(created);
        } else if (!spaces.existsById(roomCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown space: " + roomCode);
        }

        if (device.getPairedAt() != null && !roomCode.equals(device.getSpaceCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    device.getDeviceId() + " is already paired to " + device.getSpaceCode());
        }

        device.setSpaceCode(roomCode);
        device.setPairedAt(Instant.now());
        if (name != null && !name.isBlank()) {
            device.setName(name);
        }
        devices.save(device);

        // The node keeps its own copy for the OLED; the binding that matters is the one above.
        notifyNode(device.getDeviceId(), "set_space", Map.of("space_id", roomCode));
        audit.log("USER", "PAIR", device.getDeviceId(), Map.of(
                "pairing_code", code,
                "space_code", roomCode,
                "name", device.getName()));
        log.info("paired {} (code {}) to {}", device.getDeviceId(), code, roomCode);
        return device;
    }

    /**
     * Forget the node. This is a two-sided operation: the backend drops the binding, and the node is
     * told to go back to its pairing screen. Without the second half the node keeps its NVS copy and
     * the OLED keeps showing a room that no longer exists.
     */
    @Transactional
    public Device unpair(String deviceId) {
        Device device = require(deviceId);
        String previous = device.getSpaceCode();
        device.setSpaceCode(null);
        device.setPairedAt(null);
        device.setFanMode("AUTO");
        device.setFanState("OFF");
        device.setFanLevel((short) 0);
        device.setLastReason(null);
        device.setLastDecisionAt(null);
        devices.save(device);

        // Empty space_id is the "forget" signal; the firmware clears NVS and shows the pairing code.
        boolean notified = notifyNode(deviceId, "set_space", Map.of("space_id", ""));
        audit.log("USER", "UNPAIR", deviceId, Map.of(
                "previous_space_code", String.valueOf(previous),
                "node_notified", notified));
        return device;
    }

    /** The node's NVS copy is advisory only; the binding here is what the control loop uses. */
    public Optional<String> spaceCodeOf(String deviceId) {
        return devices.findById(deviceId).map(Device::getSpaceCode);
    }

    /** Patch semantics: a null field means "leave it as it is" (io_contract §7). */
    @Transactional
    public Device update(String deviceId, UpdateRequest request) {
        Device device = require(deviceId);

        if (request.name() != null && !request.name().isBlank()) {
            device.setName(request.name());
        }
        if (request.spaceCode() != null) {
            if (!request.spaceCode().isBlank() && !spaces.existsById(request.spaceCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown space: " + request.spaceCode());
            }
            device.setSpaceCode(request.spaceCode().isBlank() ? null : request.spaceCode());
            device.setPairedAt(device.getSpaceCode() == null ? null : Instant.now());
            notifyNode(deviceId, "set_space",
                    Map.of("space_id", device.getSpaceCode() == null ? "" : device.getSpaceCode()));
        }
        if (request.telemetryIntervalMs() != null) {
            int interval = Math.max(1000, Math.min(request.telemetryIntervalMs(), 60_000));
            device.setTelemetryIntervalMs(interval);
            notifyNode(deviceId, "set_interval", Map.of("interval_ms", interval));
        }
        if (request.dustBaselineUs() != null) {
            device.setDustBaselineUs(request.dustBaselineUs());
            notifyNode(deviceId, "recalibrate_dust", Map.of("baseline_w0", request.dustBaselineUs()));
        }
        devices.save(device);
        audit.log("USER", "CONFIG", deviceId, Map.of(
                "space_code", String.valueOf(device.getSpaceCode()),
                "interval_ms", device.getTelemetryIntervalMs(),
                "dust_baseline_us", device.getDustBaselineUs()));
        return device;
    }

    @Transactional
    public void delete(String deviceId) {
        devices.delete(require(deviceId));
        audit.log("USER", "DELETE", deviceId, null);
    }

    /** Manual command from the dashboard — {@code {"command": "...", "params": {...}}}. */
    @Transactional
    public Device command(String deviceId, CommandRequest request) {
        String command = request.command() == null || request.command().isBlank()
                ? "set_actuator" : request.command();
        Map<String, Object> params = request.params() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.params());

        if ("set_actuator".equals(command)) {
            String state = String.valueOf(params.getOrDefault("state", "OFF"));
            int level = params.get("level") instanceof Number number ? number.intValue() : 0;
            String mode = "MANUAL".equalsIgnoreCase(String.valueOf(params.get("mode"))) ? "MANUAL" : "AUTO";
            if (!applyActuator(deviceId, "ON".equalsIgnoreCase(state), level, mode, "USER")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "MQTT is not connected — the command reached no node");
            }
        } else if (!notifyNode(deviceId, command, params)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MQTT is not connected — the command reached no node");
        } else {
            audit.log("USER", "COMMAND", deviceId, Map.of("command", command, "params", params));
        }
        return require(deviceId);
    }

    /**
     * Sends {@code set_actuator} and records the intent. {@code mode} is the switch that makes
     * "manual override always wins" real: the firmware applies it, and {@link #onActuatorState}
     * only copies a mode the node actually reported.
     *
     * <p>A failed publish does not move the reported state. The old code wrote the new state to the
     * device row anyway, so the dashboard showed a fan that had never been told to run.
     *
     * @return whether the command reached the broker
     */
    @Transactional
    public boolean applyActuator(String deviceId, boolean on, int level, String mode, String actor) {
        Device device = require(deviceId);
        int clamped = Math.max(0, Math.min(level, 3));
        if (on && clamped == 0) {
            clamped = 2;
        }
        boolean manual = "MANUAL".equals(mode);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("state", on ? "ON" : "OFF");
        params.put("level", on ? clamped : 0);
        params.put("mode", manual ? "MANUAL" : "AUTO");

        Instant now = Instant.now();
        supersedePending(deviceId, now);
        CommandLog command = CommandLog.builder()
                .id(UUID.randomUUID())
                .deviceId(deviceId)
                .command("set_actuator")
                .params(params)
                .actor(actor)
                .requestedAt(now)
                .publishAttempts(0)
                .status("REQUESTED")
                .expiresAt(now.plus(COMMAND_TIMEOUT))
                .build();
        commands.save(command);

        boolean published = mqtt.publishCommand(deviceId, "set_actuator", params);
        command.setPublishAttempts(1);
        if (published) {
            command.setStatus("PUBLISHED");
            command.setPublishedAt(now);
        } else {
            command.setStatus("PUBLISH_FAILED");
            command.setError("broker not connected");
        }
        commands.save(command);

        if (published) {
            boolean wasOn = "ON".equals(device.getFanState());
            device.setFanState(on ? "ON" : "OFF");
            device.setFanLevel((short) (on ? clamped : 0));
            device.setFanMode(manual ? "MANUAL" : "AUTO");
            device.setLastCommandAt(now);
            if (on && !wasOn) {
                device.setOnSince(now);
            }
            if (!on && wasOn) {
                device.setOffSince(now);
            }
            if ("USER".equals(actor)) {
                // A hand on the control is a decision too, and the room screen must say so.
                device.setLastReason("manual");
                device.setLastDecisionAt(now);
            }
            devices.save(device);
        }
        audit.log(actor, on ? "ACTIVATE" : "DEACTIVATE", deviceId,
                Map.of("level", on ? clamped : 0, "mode", manual ? "MANUAL" : "AUTO",
                        "published", published, "command_id", command.getId().toString()));
        return published;
    }

    /** A newer instruction replaces an unconfirmed one; an old manual command is never replayed. */
    private void supersedePending(String deviceId, Instant now) {
        commands.findFirstByDeviceIdAndStatusInOrderByRequestedAtDesc(deviceId, PENDING_STATUSES)
                .ifPresent(existing -> {
                    existing.setStatus("SUPERSEDED");
                    existing.setExpiresAt(now);
                    commands.save(existing);
                });
    }

    /** True while an instruction is waiting for an actuator report (or its 15 s window). */
    @Transactional(readOnly = true)
    public boolean hasPendingCommand(String deviceId) {
        return commands.findFirstByDeviceIdAndStatusInOrderByRequestedAtDesc(deviceId, PENDING_STATUSES)
                .filter(command -> command.getExpiresAt() == null
                        || command.getExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public List<CommandLog> commandHistory(String deviceId, int limit) {
        return commands.findByDeviceIdOrderByRequestedAtDesc(deviceId,
                PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    @Transactional(readOnly = true)
    public List<ActuatorHistory> actuatorHistory(String deviceId, int limit) {
        return actuatorHistory.findByDeviceIdOrderByReceivedAtDesc(deviceId,
                PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    /** Records why the loop acted, so the room screen can say it in one sentence. */
    @Transactional
    public void recordDecision(Device device, String reason) {
        device.setLastReason(reason);
        device.setLastDecisionAt(Instant.now());
        devices.save(device);
    }

    /** "Đã thay lõi" — the node's own counter keeps running, so the baseline is what moves. */
    @Transactional
    public Device resetFilter(String deviceId) {
        Device device = require(deviceId);
        device.setFilterRuntimeS(0);
        device.setFilterReplacedAt(Instant.now());
        devices.save(device);
        audit.log("USER", "FILTER", deviceId, Map.of(
                "filter_runtime_s", 0,
                "replaced_at", String.valueOf(device.getFilterReplacedAt())));
        // Tell the node too: its counter is the one that persists across a power cut.
        notifyNode(deviceId, "reset_filter", Map.of());
        return device;
    }

    /**
     * Keeps the node's auto-mode lease alive. The firmware turns the fan off after 10 minutes
     * without a backend command, so a fan that is healthy but steady would otherwise be cut every
     * 10 minutes. No audit row: this is not a decision.
     *
     * <p>{@code lastCommandAt} moves only when the broker accepted the lease — otherwise the loop
     * would think it renewed successfully during an outage and stop trying.
     */
    @Transactional
    public boolean renewLease(Device device) {
        int level = device.getFanLevel() == null ? 0 : device.getFanLevel();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("state", "ON");
        params.put("level", Math.max(1, Math.min(level, 3)));
        params.put("mode", "AUTO");

        Instant now = Instant.now();
        CommandLog command = CommandLog.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getDeviceId())
                .command("set_actuator")
                .params(params)
                .actor("BACKEND")
                .requestedAt(now)
                .publishAttempts(0)
                .status("REQUESTED")
                .expiresAt(now.plus(COMMAND_TIMEOUT))
                .build();
        commands.save(command);

        boolean published = mqtt.publishCommand(device.getDeviceId(), "set_actuator", params);
        command.setPublishAttempts(1);
        if (published) {
            command.setStatus("PUBLISHED");
            command.setPublishedAt(now);
            command.setExpiresAt(now.plus(COMMAND_TIMEOUT));
            device.setLastCommandAt(now);
            devices.save(device);
        } else {
            command.setStatus("PUBLISH_FAILED");
            command.setError("broker not connected");
        }
        commands.save(command);
        return published;
    }

    /** A command that is not an actuation decision (display_warning today). */
    public boolean rawCommand(String deviceId, String command, Map<String, Object> params) {
        return mqtt.publishCommand(deviceId, command, params);
    }

    /**
     * Best-effort push to the node. Used where the database is the source of truth and broker
     * trouble must not fail the request.
     */
    private boolean notifyNode(String deviceId, String command, Map<String, Object> params) {
        return mqtt.publishCommand(deviceId, command, params);
    }

    /** {@code status ONLINE} is the liveness signal; {@code OFFLINE} (the LWT) is not. */
    @EventListener
    @Transactional
    public void onStatus(MqttLink.StatusEvent event) {
        if (event.deviceId() == null) {
            return;
        }
        Device device = ensure(event.deviceId(), null);
        rememberPairingCode(device, event.pairingCode());

        boolean online = "ONLINE".equalsIgnoreCase(event.status());
        if (online) {
            device.setLastSeenAt(event.timestamp() != null && event.timestamp() > 1_700_000_000L
                    ? Instant.ofEpochSecond(event.timestamp())
                    : Instant.now());
            devices.save(device);
        }
        if (device.getPairedAt() != null) {
            audit.log("DEVICE", "STATUS", event.deviceId(), Map.of("status", String.valueOf(event.status())));
        }
    }

    /**
     * The node's own view of its fan. It wins over ours — except that we never learn a new mode from
     * it in a way that overrides a pending manual instruction.
     *
     * <p>Every report is appended to {@code actuator_history} and then used to match a pending
     * command. A match is "a report consistent with the request", not an ACK carrying a command id.
     */
    @EventListener
    @Transactional
    public void onActuatorState(MqttLink.ActuatorStateEvent event) {
        if (event.deviceId() == null) {
            return;
        }
        Device device = ensure(event.deviceId(), null);
        Instant receivedAt = Instant.now();
        Instant recordedAt = event.timestamp() != null && event.timestamp() > 1_700_000_000L
                ? Instant.ofEpochSecond(event.timestamp())
                : null;

        String state = event.state() == null ? "OFF" : event.state();
        Short level = event.level() == null ? 0 : event.level().shortValue();
        Short duty = event.dutyPct() == null ? null : event.dutyPct().shortValue();
        Integer runtimeS = event.filterRuntimeH() == null
                ? null : Math.round(event.filterRuntimeH() * 3600f);

        Map<String, Object> quality = new LinkedHashMap<>();
        if (event.state() == null) {
            quality.put("state_missing", true);
        }
        actuatorHistory.save(ActuatorHistory.builder()
                .deviceId(event.deviceId())
                .recordedAt(recordedAt)
                .receivedAt(receivedAt)
                .state(state)
                .level(level)
                .dutyPct(duty)
                .mode(event.mode())
                .filterRuntimeS(runtimeS)
                .quality(quality)
                .build());

        String previousState = device.getFanState();
        device.setFanState(state);
        device.setFanLevel(level);
        if (event.mode() != null) {
            device.setFanMode(event.mode());
        }
        // The node counts fan hours itself; this was being received and discarded.
        if (runtimeS != null) {
            device.setFilterRuntimeS(runtimeS);
        }
        if ("ON".equals(state) && !"ON".equals(previousState)) {
            device.setOnSince(receivedAt);
        }
        if ("OFF".equals(state) && !"OFF".equals(previousState)) {
            device.setOffSince(receivedAt);
        }
        device.setLastSeenAt(receivedAt);
        device.setLastActuatorReceivedAt(receivedAt);
        devices.save(device);

        matchPendingCommand(device, state, level, event.mode(), receivedAt);
    }

    private void matchPendingCommand(Device device, String state, Short level, String mode, Instant at) {
        commands.findFirstByDeviceIdAndStatusInOrderByRequestedAtDesc(
                        device.getDeviceId(), List.of("PUBLISHED"))
                .ifPresent(command -> {
                    if (command.getExpiresAt() != null && command.getExpiresAt().isBefore(at)) {
                        return;   // too old to attribute
                    }
                    if (targetMatches(command.getParams(), state, level, mode)) {
                        command.setStatus("REPORT_MATCHED");
                        command.setMatchedAt(at);
                        commands.save(command);
                    }
                });
    }

    private static boolean targetMatches(Map<String, Object> params, String state, Short level, String mode) {
        if (params == null) {
            return false;
        }
        Object targetState = params.get("state");
        if (targetState != null && !String.valueOf(targetState).equalsIgnoreCase(state)) {
            return false;
        }
        Object targetLevel = params.get("level");
        if (targetLevel instanceof Number number && level != null && number.intValue() != level.intValue()) {
            return false;
        }
        Object targetMode = params.get("mode");
        return targetMode == null || mode == null || String.valueOf(targetMode).equalsIgnoreCase(mode);
    }

    public record UpdateRequest(String name, String spaceCode, Integer telemetryIntervalMs, Float dustBaselineUs) {
    }

    public record CommandRequest(String command, Map<String, Object> params) {
    }

    public record PairRequest(String pairingCode, String spaceCode, String name, String spaceName,
                              String purpose, Float volumeM3, Integer occupancyHours) {
    }

    private static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return CODE_NOISE.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
