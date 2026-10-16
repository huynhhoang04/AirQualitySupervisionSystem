package com.aqss.backend.service;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.dto.actuator.ActuatorDto;
import com.aqss.backend.dto.actuator.ManualControlRequest;
import com.aqss.backend.dto.mqtt.ActuatorStateMessage;
import com.aqss.backend.entity.Actuator;
import com.aqss.backend.entity.ActuatorMode;
import com.aqss.backend.entity.ActuatorState;
import com.aqss.backend.entity.DecisionAction;
import com.aqss.backend.entity.DecisionMode;
import com.aqss.backend.entity.Space;
import com.aqss.backend.exception.BadRequestException;
import com.aqss.backend.mapper.ActuatorMapper;
import com.aqss.backend.mqtt.MqttCommandPublisher;
import com.aqss.backend.repository.ActuatorRepository;
import com.aqss.backend.service.rules.DosageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives the filter fan and enforces the safety constraints (min ON/OFF, max ON, manual override,
 * fail-safe). Publishes {@code set_actuator} / {@code display_warning} and tracks live state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActuatorService {

    public static final String TOPIC_ACTUATORS = "/topic/actuators/";

    /** LEDC duty per discrete level — never below the ~20 % BLDC stall duty. */
    private static final short[] DUTY_BY_LEVEL = {0, 35, 60, 90};

    private final ActuatorRepository actuatorRepository;
    private final MqttCommandPublisher commandPublisher;
    private final ActuatorMapper actuatorMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final DecisionAuditService decisionAuditService;
    private final AqssProperties properties;

    @Transactional(readOnly = true)
    public List<ActuatorDto> findAll() {
        return actuatorRepository.findAll().stream().map(actuatorMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Actuator> find(Space space) {
        return actuatorRepository.findFirstBySpaceId(space.getId());
    }

    @Transactional(readOnly = true)
    public boolean isOn(Space space) {
        return find(space).map(a -> a.getCurrentState() == ActuatorState.ON).orElse(false);
    }

    @Transactional(readOnly = true)
    public double onDurationMinutes(Space space) {
        return find(space)
                .filter(a -> a.getCurrentState() == ActuatorState.ON && a.getStateSince() != null)
                .map(a -> Duration.between(a.getStateSince(), Instant.now()).toSeconds() / 60.0)
                .orElse(0.0);
    }

    /** AUTO activation. Respects manual override, minimum OFF time and no-op re-commands. */
    @Transactional
    public boolean activate(Space space, DosageResult dosage) {
        Optional<Actuator> maybe = find(space);
        if (maybe.isEmpty()) {
            log.warn("No actuator bound to space {}", space.getCode());
            return false;
        }
        Actuator actuator = maybe.get();
        if (isManual(actuator)) {
            log.debug("Space {} is MANUAL — AUTO activation suppressed", space.getCode());
            return false;
        }
        boolean on = actuator.getCurrentState() == ActuatorState.ON;
        if (on && actuator.getCurrentLevel() != null && actuator.getCurrentLevel() == dosage.level()) {
            return false;
        }
        if (!on && actuator.getLastCommandAt() != null
                && minutesSince(actuator.getLastCommandAt()) < properties.control().minOffMinutes()) {
            return false;
        }
        sendSetActuator(actuator, ActuatorState.ON, dosage.level());
        return true;
    }

    /**
     * @param force bypasses the minimum ON time (reserved for the maximum-ON safety cut-off)
     */
    @Transactional
    public boolean deactivate(Space space, boolean force) {
        Optional<Actuator> maybe = find(space);
        if (maybe.isEmpty()) {
            return false;
        }
        Actuator actuator = maybe.get();
        if (isManual(actuator)) {
            return false;
        }
        if (actuator.getCurrentState() != ActuatorState.ON) {
            return false;
        }
        if (!force && actuator.getStateSince() != null
                && minutesSince(actuator.getStateSince()) < properties.control().minOnMinutes()) {
            return false;
        }
        sendSetActuator(actuator, ActuatorState.OFF, (short) 0);
        return true;
    }

    /** Soft alert: renders a forecast-based early warning on the node OLED. */
    @Transactional
    public void warn(Space space, double hoursToBreach) {
        find(space).ifPresent(actuator -> commandPublisher.publish(actuator.getDeviceId(), "display_warning",
                Map.of("message", "Air quality may decline. Forecast breach in %.1fh.".formatted(hoursToBreach),
                        "severity", "WARNING")));
    }

    /** Manual override from the dashboard. MANUAL always wins; AUTO hands control back. */
    @Transactional
    public void applyManual(Space space, ManualControlRequest request) {
        Actuator actuator = find(space).orElseThrow(
                () -> new BadRequestException("No actuator bound to space " + space.getCode()));
        ActuatorMode mode = ActuatorMode.valueOf(request.mode());

        if (mode == ActuatorMode.AUTO) {
            actuator.setMode(ActuatorMode.AUTO);
            actuatorRepository.save(actuator);
            log.info("Space {} returned to AUTO control", space.getCode());
            return;
        }

        ActuatorState state = ActuatorState.valueOf(request.state());
        short level = state == ActuatorState.OFF ? 0 : (request.level() == null ? 1 : request.level().shortValue());
        actuator.setMode(ActuatorMode.MANUAL);
        sendSetActuator(actuator, state, level);
        decisionAuditService.record(space, state == ActuatorState.ON ? DecisionAction.ACTIVATE : DecisionAction.DEACTIVATE,
                DecisionMode.MANUAL, "manual", null, null, level, null);
    }

    /** Reflects device-reported state back into the registry and the dashboard. */
    @Transactional
    public void updateStateFromDevice(ActuatorStateMessage message) {
        Actuator actuator = actuatorRepository.findById(message.deviceId())
                .orElseGet(() -> Actuator.builder().deviceId(message.deviceId()).build());

        ActuatorState newState = "ON".equalsIgnoreCase(message.state()) ? ActuatorState.ON : ActuatorState.OFF;
        boolean wasOn = actuator.getCurrentState() == ActuatorState.ON;
        if (actuator.getStateSince() == null || wasOn != (newState == ActuatorState.ON)) {
            actuator.setStateSince(Instant.now());
        }
        actuator.setCurrentState(newState);
        actuator.setCurrentLevel(message.level() == null ? (short) 0 : message.level().shortValue());
        actuator.setDutyPct(message.dutyPct() == null ? null : message.dutyPct().shortValue());
        if (message.mode() != null) {
            actuator.setMode(ActuatorMode.valueOf(message.mode()));
        }
        if (message.filterRuntimeH() != null) {
            actuator.setFilterRuntimeH(message.filterRuntimeH());
        }
        Actuator saved = actuatorRepository.save(actuator);
        messagingTemplate.convertAndSend(TOPIC_ACTUATORS + message.deviceId(), actuatorMapper.toDto(saved));
    }

    private void sendSetActuator(Actuator actuator, ActuatorState state, short level) {
        commandPublisher.publish(actuator.getDeviceId(), "set_actuator",
                Map.of("state", state.name(), "level", level));
        Instant now = Instant.now();
        actuator.setCurrentState(state);
        actuator.setCurrentLevel(level);
        actuator.setDutyPct(level >= 0 && level < DUTY_BY_LEVEL.length ? DUTY_BY_LEVEL[level] : 0);
        actuator.setLastCommandAt(now);
        actuator.setStateSince(now);
        Actuator saved = actuatorRepository.save(actuator);
        messagingTemplate.convertAndSend(TOPIC_ACTUATORS + actuator.getDeviceId(), actuatorMapper.toDto(saved));
    }

    private static boolean isManual(Actuator actuator) {
        return actuator.getMode() == ActuatorMode.MANUAL;
    }

    private static double minutesSince(Instant instant) {
        return Duration.between(instant, Instant.now()).toSeconds() / 60.0;
    }
}
