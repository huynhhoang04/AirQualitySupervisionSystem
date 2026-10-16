package com.aqss.backend.controller;

import com.aqss.backend.dto.actuator.ManualControlRequest;
import com.aqss.backend.dto.common.StatusResponse;
import com.aqss.backend.dto.decision.DecisionDto;
import com.aqss.backend.dto.space.AssignDeviceRequest;
import com.aqss.backend.dto.space.CreateSpaceRequest;
import com.aqss.backend.dto.space.SpaceDto;
import com.aqss.backend.dto.space.SpaceSummaryDto;
import com.aqss.backend.dto.space.ThresholdDto;
import com.aqss.backend.dto.space.UpdateSpaceRequest;
import com.aqss.backend.entity.Space;
import com.aqss.backend.entity.SpaceThreshold;
import com.aqss.backend.service.ActuatorService;
import com.aqss.backend.service.SpaceService;
import com.aqss.backend.service.ThresholdService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Space management + the manual actuator override. */
@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;
    private final ThresholdService thresholdService;
    private final ActuatorService actuatorService;

    @GetMapping
    public List<SpaceSummaryDto> list() {
        return spaceService.listSummaries();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpaceDto create(@Valid @RequestBody CreateSpaceRequest request) {
        return spaceService.toDto(spaceService.create(request));
    }

    @GetMapping("/{id}")
    public SpaceDto get(@PathVariable String id) {
        return spaceService.getDto(spaceService.resolve(id).getId());
    }

    @PutMapping("/{id}")
    public SpaceDto update(@PathVariable String id, @Valid @RequestBody UpdateSpaceRequest request) {
        Space space = spaceService.resolve(id);
        return spaceService.toDto(spaceService.update(space.getId(), request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        spaceService.delete(spaceService.resolve(id).getId());
    }

    @PostMapping("/{id}/devices")
    public StatusResponse assignDevice(@PathVariable String id, @Valid @RequestBody AssignDeviceRequest request) {
        spaceService.assignDevice(spaceService.resolve(id).getId(), request);
        return StatusResponse.of("assigned");
    }

    @GetMapping("/{id}/decisions")
    public List<DecisionDto> decisions(@PathVariable String id,
                                       @RequestParam(defaultValue = "50") int limit) {
        return spaceService.decisions(spaceService.resolve(id).getId(), limit);
    }

    @PostMapping("/{id}/actuator")
    public StatusResponse control(@PathVariable String id, @Valid @RequestBody ManualControlRequest request) {
        actuatorService.applyManual(spaceService.resolve(id), request);
        return StatusResponse.of("sent");
    }

    @PostMapping("/{id}/threshold")
    public ThresholdDto recomputeThreshold(@PathVariable String id) {
        SpaceThreshold threshold = thresholdService.recompute(spaceService.resolve(id));
        return new ThresholdDto(threshold.getBaseThresholdPm25(), threshold.getUncertaintyLower(),
                threshold.getUncertaintyUpper(), threshold.getModelVersion());
    }
}
