package com.aqss.backend.service;

import com.aqss.backend.model.Device;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceRepository;
import com.aqss.backend.mqtt.MqttLink;
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
import java.util.Map;

/** Device registry, configuration and every command sent to a node. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final MqttLink mqtt;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<Device> findAll() {
        return devices.findAll();
    }

    @Transactional(readOnly = true)
    public Device require(String deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + deviceId));
    }

    /** A node that publishes telemetry is registered on the spot — no provisioning step. */
    @Transactional
    public Device ensure(String deviceId, String spaceCode) {
        return devices.findById(deviceId).orElseGet(() -> {
            Device created = Device.builder()
                    .deviceId(deviceId)
                    .name(deviceId)
                    .spaceCode(spaceCode)
                    .fanState("OFF")
                    .fanLevel((short) 0)
                    .fanMode("AUTO")
                    .telemetryIntervalMs(5000)
                    .dustBaselineUs(1400f)
                    .createdAt(Instant.now())
                    .build();
            log.info("auto-registered device {}", deviceId);
            return devices.save(created);
        });
    }

    /** Name / space / telemetry interval / dust baseline — pushed to the node as commands. */
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
            device.setSpaceCode(request.spaceCode());
            mqtt.publishCommand(deviceId, "set_space", Map.of("space_id", request.spaceCode()));
        }
        if (request.telemetryIntervalMs() != null) {
            device.setTelemetryIntervalMs(request.telemetryIntervalMs());
            mqtt.publishCommand(deviceId, "set_interval", Map.of("interval_ms", request.telemetryIntervalMs()));
        }
        if (request.dustBaselineUs() != null) {
            device.setDustBaselineUs(request.dustBaselineUs());
            mqtt.publishCommand(deviceId, "recalibrate_dust", Map.of("baseline_w0", request.dustBaselineUs()));
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
            applyActuator(deviceId, "ON".equalsIgnoreCase(state), level, "USER");
        } else {
            mqtt.publishCommand(deviceId, command, params);
            audit.log("USER", "COMMAND", deviceId, Map.of("command", command, "params", params));
        }
        return require(deviceId);
    }

    /** Used by the dashboard (actor USER) and by the control loop (actor BACKEND). */
    @Transactional
    public void applyActuator(String deviceId, boolean on, int level, String actor) {
        Device device = require(deviceId);
        mqtt.publishCommand(deviceId, "set_actuator",
                Map.of("state", on ? "ON" : "OFF", "level", on ? level : 0));

        boolean wasOn = "ON".equals(device.getFanState());
        device.setFanState(on ? "ON" : "OFF");
        device.setFanLevel((short) (on ? level : 0));
        device.setFanMode("USER".equals(actor) ? "MANUAL" : "AUTO");
        device.setLastCommandAt(Instant.now());
        if (on && !wasOn) {
            device.setOnSince(Instant.now());
        }
        devices.save(device);
        audit.log(actor, on ? "ACTIVATE" : "DEACTIVATE", deviceId, Map.of("level", on ? level : 0));
    }

    @EventListener
    @Transactional
    public void onStatus(MqttLink.StatusEvent event) {
        if (event.deviceId() == null) {
            return;
        }
        Device device = ensure(event.deviceId(), null);
        device.setLastSeenAt(event.timestamp() != null ? Instant.ofEpochSecond(event.timestamp()) : Instant.now());
        devices.save(device);
        audit.log("DEVICE", "STATUS", event.deviceId(), Map.of("status", String.valueOf(event.status())));
    }

    @EventListener
    @Transactional
    public void onActuatorState(MqttLink.ActuatorStateEvent event) {
        if (event.deviceId() == null) {
            return;
        }
        Device device = ensure(event.deviceId(), null);
        device.setFanState(event.state() == null ? "OFF" : event.state());
        device.setFanLevel(event.level() == null ? (short) 0 : event.level().shortValue());
        if (event.mode() != null) {
            device.setFanMode(event.mode());
        }
        devices.save(device);
    }

    public record UpdateRequest(String name, String spaceCode, Integer telemetryIntervalMs, Float dustBaselineUs) {
    }

    public record CommandRequest(String command, Map<String, Object> params) {
    }
}
