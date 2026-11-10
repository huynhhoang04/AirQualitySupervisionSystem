package com.aqss.backend.service;

import com.aqss.backend.model.Device;
import com.aqss.backend.mqtt.MqttLink;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Device registry, pairing, configuration and every command sent to a node. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    /** Codes arrive from a phone keyboard; strip anything that is not a symbol A–Z / 2–9. */
    private static final Pattern CODE_NOISE = Pattern.compile("[^A-Z2-9]");

    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final MqttLink mqtt;
    private final AuditService audit;

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
     * A node is registered the first time it is heard from — but unpaired. The room binding is set
     * by {@link #pair}, never by whatever the node happened to have in NVS (io_contract §7.1).
     */
    @Transactional
    public Device ensure(String deviceId, String spaceCode) {
        Device device = devices.findById(deviceId).orElseGet(() -> {
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
            log.info("registered unpaired device {} (space hint ignored: {})", deviceId, spaceCode);
            return devices.save(created);
        });
        if (device.getSpaceCode() == null && spaceCode != null && spaces.existsById(spaceCode)) {
            // A row created before pairing existed (or seeded by hand) may already know its room.
            device.setSpaceCode(spaceCode);
            device.setPairedAt(Instant.now());
            devices.save(device);
        }
        return device;
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

    /** {@code POST /devices/pair} — the only way a node becomes visible on the dashboard. */
    @Transactional
    public Device pair(String rawCode, String spaceCode, String name) {
        String code = normalise(rawCode);
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pairing_code is required");
        }
        if (spaceCode == null || spaceCode.isBlank() || !spaces.existsById(spaceCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown space: " + spaceCode);
        }
        Device device = devices.findFirstByPairingCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no node has connected with code " + code + " yet"));

        if (device.getPairedAt() != null && !spaceCode.equals(device.getSpaceCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    device.getDeviceId() + " is already paired to " + device.getSpaceCode());
        }

        device.setSpaceCode(spaceCode);
        device.setPairedAt(Instant.now());
        if (name != null && !name.isBlank()) {
            device.setName(name);
        }
        devices.save(device);

        // The node keeps its own copy for the OLED; the binding that matters is the one above.
        notifyNode(device.getDeviceId(), "set_space", Map.of("space_id", spaceCode));
        audit.log("USER", "PAIR", device.getDeviceId(), Map.of(
                "pairing_code", code,
                "space_code", spaceCode,
                "name", device.getName()));
        log.info("paired {} (code {}) to {}", device.getDeviceId(), code, spaceCode);
        return device;
    }

    @Transactional
    public Device unpair(String deviceId) {
        Device device = require(deviceId);
        String previous = device.getSpaceCode();
        device.setSpaceCode(null);
        device.setPairedAt(null);
        device.setFanMode("AUTO");
        device.setFanState("OFF");
        device.setFanLevel((short) 0);
        devices.save(device);
        audit.log("USER", "UNPAIR", deviceId, Map.of("previous_space_code", String.valueOf(previous)));
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
        boolean published = mqtt.publishCommand(deviceId, "set_actuator", params);

        boolean wasOn = "ON".equals(device.getFanState());
        Instant now = Instant.now();
        device.setFanState(on ? "ON" : "OFF");
        device.setFanLevel((short) (on ? clamped : 0));
        device.setFanMode(manual ? "MANUAL" : "AUTO");
        if (published) {
            device.setLastCommandAt(now);
        }
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
        audit.log(actor, on ? "ACTIVATE" : "DEACTIVATE", deviceId,
                Map.of("level", on ? clamped : 0, "mode", manual ? "MANUAL" : "AUTO",
                        "published", published));
        return published;
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
     */
    @Transactional
    public void renewLease(Device device) {
        int level = device.getFanLevel() == null ? 0 : device.getFanLevel();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("state", "ON");
        params.put("level", Math.max(1, Math.min(level, 3)));
        params.put("mode", "AUTO");
        mqtt.publishCommand(device.getDeviceId(), "set_actuator", params);
        device.setLastCommandAt(Instant.now());
        devices.save(device);
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

    /** The node's own view of its fan. It wins over ours — except that we never learn a new mode from it. */
    @EventListener
    @Transactional
    public void onActuatorState(MqttLink.ActuatorStateEvent event) {
        if (event.deviceId() == null) {
            return;
        }
        Device device = ensure(event.deviceId(), null);
        Instant now = Instant.now();

        String previousState = device.getFanState();
        device.setFanState(event.state() == null ? "OFF" : event.state());
        device.setFanLevel(event.level() == null ? (short) 0 : event.level().shortValue());
        if (event.mode() != null) {
            device.setFanMode(event.mode());
        }
        // The node counts fan hours itself; this was being received and discarded.
        if (event.filterRuntimeH() != null) {
            device.setFilterRuntimeS(Math.round(event.filterRuntimeH() * 3600f));
        }
        if ("ON".equals(device.getFanState()) && !"ON".equals(previousState)) {
            device.setOnSince(now);
        }
        if ("OFF".equals(device.getFanState()) && !"OFF".equals(previousState)) {
            device.setOffSince(now);
        }
        device.setLastSeenAt(now);
        devices.save(device);
    }

    public record UpdateRequest(String name, String spaceCode, Integer telemetryIntervalMs, Float dustBaselineUs) {
    }

    public record CommandRequest(String command, Map<String, Object> params) {
    }

    public record PairRequest(String pairingCode, String spaceCode, String name) {
    }

    private static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return CODE_NOISE.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
