package com.aqss.backend.service;

import com.aqss.backend.dto.decision.DecisionDto;
import com.aqss.backend.dto.space.AssignDeviceRequest;
import com.aqss.backend.dto.space.CreateSpaceRequest;
import com.aqss.backend.dto.space.DosageDto;
import com.aqss.backend.dto.space.SpaceDto;
import com.aqss.backend.dto.space.SpaceSummaryDto;
import com.aqss.backend.dto.space.ThresholdDto;
import com.aqss.backend.dto.space.UpdateSpaceRequest;
import com.aqss.backend.entity.Actuator;
import com.aqss.backend.entity.ActuatorMode;
import com.aqss.backend.entity.ActuatorState;
import com.aqss.backend.entity.Device;
import com.aqss.backend.entity.DeviceRole;
import com.aqss.backend.entity.Space;
import com.aqss.backend.entity.SpaceDevice;
import com.aqss.backend.entity.SpaceDeviceId;
import com.aqss.backend.entity.SpaceThreshold;
import com.aqss.backend.exception.ConflictException;
import com.aqss.backend.exception.NotFoundException;
import com.aqss.backend.mapper.DecisionMapper;
import com.aqss.backend.mqtt.MqttCommandPublisher;
import com.aqss.backend.repository.ActuatorRepository;
import com.aqss.backend.repository.ControlDecisionRepository;
import com.aqss.backend.repository.DeviceRepository;
import com.aqss.backend.repository.SpaceDeviceRepository;
import com.aqss.backend.repository.SpaceRepository;
import com.aqss.backend.service.rules.DosageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Space CRUD, node assignment and DTO assembly. Changing a space recomputes its ML#1 threshold. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final SpaceDeviceRepository spaceDeviceRepository;
    private final ActuatorRepository actuatorRepository;
    private final DeviceRepository deviceRepository;
    private final ControlDecisionRepository decisionRepository;
    private final ThresholdService thresholdService;
    private final DosageService dosageService;
    private final TelemetryQueryService telemetryQueryService;
    private final DecisionMapper decisionMapper;
    private final MqttCommandPublisher commandPublisher;

    @Transactional(readOnly = true)
    public List<Space> findAll() {
        return spaceRepository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Space findById(Long id) {
        return spaceRepository.findById(id).orElseThrow(() -> NotFoundException.of("Space", id));
    }

    @Transactional(readOnly = true)
    public Space findByCode(String code) {
        return spaceRepository.findByCode(code).orElseThrow(() -> NotFoundException.of("Space", code));
    }

    /** Accepts either the numeric id or the device-facing code ({@code SP_201}). */
    @Transactional(readOnly = true)
    public Space resolve(String idOrCode) {
        if (idOrCode != null && idOrCode.matches("\\d+")) {
            return findById(Long.valueOf(idOrCode));
        }
        return findByCode(idOrCode);
    }

    @Transactional
    public List<SpaceSummaryDto> listSummaries() {
        return findAll().stream().map(this::toSummary).toList();
    }

    @Transactional
    public SpaceDto getDto(Long id) {
        return toDto(findById(id));
    }

    @Transactional
    public Space create(CreateSpaceRequest request) {
        if (spaceRepository.existsByCode(request.code())) {
            throw new ConflictException("Space code already exists: " + request.code());
        }
        Space space = Space.builder()
                .code(request.code())
                .name(request.name())
                .areaM2(request.areaM2())
                .ceilingM(request.ceilingM())
                .volumeM3(request.volumeM3())
                .purpose(request.purpose())
                .region(request.region())
                .ventilationEstimate(request.ventilationEstimate() == null ? "low" : request.ventilationEstimate())
                .createdAt(Instant.now())
                .build();
        Space saved = spaceRepository.save(space);
        thresholdService.recompute(saved);
        return saved;
    }

    @Transactional
    public Space update(Long id, UpdateSpaceRequest request) {
        Space space = findById(id);
        space.setName(request.name());
        if (request.areaM2() != null) {
            space.setAreaM2(request.areaM2());
        }
        if (request.ceilingM() != null) {
            space.setCeilingM(request.ceilingM());
        }
        if (request.volumeM3() != null) {
            space.setVolumeM3(request.volumeM3());
        }
        if (request.purpose() != null) {
            space.setPurpose(request.purpose());
        }
        if (request.region() != null) {
            space.setRegion(request.region());
        }
        if (request.ventilationEstimate() != null) {
            space.setVentilationEstimate(request.ventilationEstimate());
        }
        Space saved = spaceRepository.save(space);
        thresholdService.recompute(saved);   // invalidate + recompute ML#1 on config change
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        spaceRepository.delete(findById(id));
    }

    @Transactional
    public void assignDevice(Long spaceId, AssignDeviceRequest request) {
        Space space = findById(spaceId);
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> NotFoundException.of("Device", request.deviceId()));
        DeviceRole role = request.role() == null ? DeviceRole.COMBINED : DeviceRole.valueOf(request.role());

        SpaceDeviceId key = new SpaceDeviceId(spaceId, device.getDeviceId());
        if (!spaceDeviceRepository.existsById(key)) {
            spaceDeviceRepository.save(SpaceDevice.builder().id(key).role(role).build());
        }

        Actuator actuator = actuatorRepository.findById(device.getDeviceId())
                .orElseGet(() -> Actuator.builder()
                        .deviceId(device.getDeviceId())
                        .currentState(ActuatorState.OFF)
                        .currentLevel((short) 0)
                        .mode(ActuatorMode.AUTO)
                        .dutyPct((short) 0)
                        .filterRuntimeH(0f)
                        .stateSince(Instant.now())
                        .build());
        actuator.setSpaceId(spaceId);
        actuatorRepository.save(actuator);

        // Bind the node to the space (persisted to NVS by the firmware).
        commandPublisher.publish(device.getDeviceId(), "set_space", Map.of("space_id", space.getCode()));
        log.info("Device {} assigned to space {} as {}", device.getDeviceId(), space.getCode(), role);
    }

    @Transactional(readOnly = true)
    public List<DecisionDto> decisions(Long spaceId, int limit) {
        findById(spaceId);
        return decisionRepository.findBySpaceIdOrderByDecidedAtDesc(spaceId, PageRequest.of(0, limit)).stream()
                .map(decisionMapper::toDto)
                .toList();
    }

    public SpaceDto toDto(Space space) {
        SpaceThreshold threshold = thresholdService.getOrCompute(space);
        List<String> devices = spaceDeviceRepository.findByIdSpaceId(space.getId()).stream()
                .map(sd -> sd.getId().getDeviceId()).toList();
        List<String> actuators = actuatorRepository.findBySpaceId(space.getId()).stream()
                .map(Actuator::getDeviceId).toList();

        double referencePm25 = Optional.ofNullable(telemetryQueryService.rollingMedianPm25(space.getCode()))
                .orElse(threshold.getBaseThresholdPm25() * 1.2);
        DosageResult dosage = dosageService.compute(space, referencePm25, threshold.getBaseThresholdPm25());

        return new SpaceDto(
                space.getId(), space.getCode(), space.getName(), space.getAreaM2(), space.getCeilingM(),
                space.getVolumeM3(), space.getPurpose(), space.getRegion(), space.getVentilationEstimate(),
                devices, actuators,
                new ThresholdDto(threshold.getBaseThresholdPm25(), threshold.getUncertaintyLower(),
                        threshold.getUncertaintyUpper(), threshold.getModelVersion()),
                new DosageDto(dosage.level(), dosage.durationMinutes()));
    }

    public SpaceSummaryDto toSummary(Space space) {
        return new SpaceSummaryDto(space.getId(), space.getCode(), space.getName(), space.getVolumeM3(),
                space.getPurpose(), space.getRegion(), space.getVentilationEstimate(),
                thresholdService.getOrCompute(space).getBaseThresholdPm25());
    }
}
