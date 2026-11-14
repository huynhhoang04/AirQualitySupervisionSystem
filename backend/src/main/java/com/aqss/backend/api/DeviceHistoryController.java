package com.aqss.backend.api;

import com.aqss.backend.model.ActuatorHistory;
import com.aqss.backend.model.CommandLog;
import com.aqss.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Command lifecycle and reported actuator history for one node. */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceHistoryController {

    private final DeviceService devices;

    /** Best-effort uploads, in request order. {@code REPORT_MATCHED} means a report agreed with it. */
    @GetMapping("/{deviceId}/commands")
    public List<CommandLog> commands(@PathVariable String deviceId,
                                     @RequestParam(defaultValue = "100") int limit) {
        return devices.commandHistory(deviceId, limit);
    }

    /** What the device actually reported, append-only. This is the state's source of truth. */
    @GetMapping("/{deviceId}/actuator-history")
    public List<ActuatorHistory> actuatorHistory(@PathVariable String deviceId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return devices.actuatorHistory(deviceId, limit);
    }
}
