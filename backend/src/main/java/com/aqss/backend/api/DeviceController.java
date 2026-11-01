package com.aqss.backend.api;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.service.DeviceService;
import com.aqss.backend.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Everything the dashboard needs about a node: state, history, forecast, commands. */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService devices;
    private final TelemetryService telemetry;

    @GetMapping
    public List<Device> list() {
        return devices.findAll();
    }

    @GetMapping("/{deviceId}")
    public Device get(@PathVariable String deviceId) {
        return devices.require(deviceId);
    }

    @PutMapping("/{deviceId}")
    public Device update(@PathVariable String deviceId, @RequestBody DeviceService.UpdateRequest request) {
        return devices.update(deviceId, request);
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> delete(@PathVariable String deviceId) {
        devices.delete(deviceId);
        return ResponseEntity.noContent().build();
    }

    /** Manual control — {@code {"command":"set_actuator","params":{"state":"ON","level":2}}}. */
    @PostMapping("/{deviceId}/command")
    public Device command(@PathVariable String deviceId, @RequestBody DeviceService.CommandRequest request) {
        return devices.command(deviceId, request);
    }

    @GetMapping("/{deviceId}/latest")
    public Telemetry latest(@PathVariable String deviceId) {
        return telemetry.latest(deviceId);
    }

    /** Chart data: raw readings between two instants (defaults to the last 24 h). */
    @GetMapping("/{deviceId}/telemetry")
    public List<Telemetry> history(@PathVariable String deviceId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to) {
        return telemetry.history(deviceId, from, to);
    }

    /** The cached 96-point forecast from Layer 3 (empty list until one is produced). */
    @GetMapping("/{deviceId}/forecast")
    public String forecast(@PathVariable String deviceId) {
        return telemetry.storedForecast(deviceId);
    }

    /** Ask Layer 3 for a fresh forecast and store it. */
    @PostMapping("/{deviceId}/forecast")
    public String refreshForecast(@PathVariable String deviceId) {
        return telemetry.refreshForecast(deviceId);
    }
}
