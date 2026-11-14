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

/** Everything the dashboard needs about a node: pairing, state, history and commands. */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService devices;
    private final TelemetryService telemetry;

    /** Paired devices only; {@code ?include_unpaired=true} returns rows waiting to be paired. */
    @GetMapping
    public List<Device> list(@RequestParam(name = "include_unpaired", defaultValue = "false") boolean includeUnpaired) {
        return devices.findAll(includeUnpaired);
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

    /** {@code {"pairing_code":"7K2M9X","space_name":"Phòng ngủ"}} — io_contract §7.1. */
    @PostMapping("/pair")
    public Device pair(@RequestBody DeviceService.PairRequest request) {
        return devices.pair(request.pairingCode(), request.spaceCode(), request.name(),
                request.spaceName(), request.purpose(), request.volumeM3(), request.occupancyHours());
    }

    @PostMapping("/{deviceId}/unpair")
    public Device unpair(@PathVariable String deviceId) {
        return devices.unpair(deviceId);
    }

    /** "Đã thay lõi lọc" — resets the hours counter and tells the node. */
    @PostMapping("/{deviceId}/filter")
    public Device resetFilter(@PathVariable String deviceId) {
        return devices.resetFilter(deviceId);
    }

    /** Manual control — {@code {"command":"set_actuator","params":{"state":"ON","level":2,"mode":"MANUAL"}}}. */
    @PostMapping("/{deviceId}/command")
    public Device command(@PathVariable String deviceId, @RequestBody DeviceService.CommandRequest request) {
        return devices.command(deviceId, request);
    }

    @GetMapping("/{deviceId}/latest")
    public Telemetry latest(@PathVariable String deviceId) {
        return telemetry.latest(deviceId);
    }

    /** Chart data: raw readings between two instants (defaults to the last 24 h, thinned to ~1500 points). */
    @GetMapping("/{deviceId}/telemetry")
    public List<Telemetry> history(@PathVariable String deviceId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to) {
        return telemetry.history(deviceId, from, to);
    }

}
