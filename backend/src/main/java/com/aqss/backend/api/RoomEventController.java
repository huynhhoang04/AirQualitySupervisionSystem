package com.aqss.backend.api;

import com.aqss.backend.model.Experiment;
import com.aqss.backend.model.RoomEvent;
import com.aqss.backend.service.RoomEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Room events and observation sessions. These are context, never required by a model. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoomEventController {

    private final RoomEventService roomEvents;

    @PostMapping("/devices/{deviceId}/events")
    public RoomEvent record(@PathVariable String deviceId, @RequestBody RoomEventService.EventRequest request) {
        return roomEvents.record(deviceId, request);
    }

    @GetMapping("/devices/{deviceId}/events")
    public List<RoomEvent> list(@PathVariable String deviceId,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to) {
        return roomEvents.list(deviceId, from, to);
    }

    @PostMapping("/experiments")
    public Experiment start(@RequestBody RoomEventService.ExperimentRequest request) {
        return roomEvents.startExperiment(request);
    }

    @GetMapping("/experiments")
    public List<Experiment> listExperiments(@RequestParam(name = "device_id") String deviceId) {
        return roomEvents.experiments(deviceId);
    }
}
