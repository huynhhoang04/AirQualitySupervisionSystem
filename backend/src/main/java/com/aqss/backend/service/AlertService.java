package com.aqss.backend.service;

import com.aqss.backend.entity.Alert;
import com.aqss.backend.entity.AlertType;
import com.aqss.backend.entity.Severity;
import com.aqss.backend.mapper.AlertMapper;
import com.aqss.backend.dto.alert.AlertDto;
import com.aqss.backend.exception.NotFoundException;
import com.aqss.backend.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Creates, broadcasts and resolves alerts. CO2 alerts are advisory only. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    public static final String TOPIC_ALERTS = "/topic/alerts";

    private final AlertRepository alertRepository;
    private final AlertMapper alertMapper;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Raises an alert unless an unresolved one of the same type already exists for the device
     * (prevents alert storms on a 5-second telemetry cadence).
     */
    @Transactional
    public Optional<Alert> raise(Long spaceId, String deviceId, AlertType type, Severity severity, String message) {
        boolean duplicate = deviceId != null
                ? alertRepository.existsByDeviceIdAndAlertTypeAndResolvedAtIsNull(deviceId, type)
                : spaceId != null && alertRepository.existsBySpaceIdAndAlertTypeAndResolvedAtIsNull(spaceId, type);
        if (duplicate) {
            return Optional.empty();
        }
        Alert alert = Alert.builder()
                .spaceId(spaceId)
                .deviceId(deviceId)
                .alertType(type)
                .severity(severity)
                .message(message)
                .triggeredAt(Instant.now())
                .build();
        Alert saved = alertRepository.save(alert);
        messagingTemplate.convertAndSend(TOPIC_ALERTS, alertMapper.toDto(saved));
        log.info("Alert raised [{}] {}: {}", severity, type, message);
        return Optional.of(saved);
    }

    @Transactional(readOnly = true)
    public List<AlertDto> findAll() {
        return alertRepository.findAllByOrderByTriggeredAtDesc().stream().map(alertMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertDto> findActive() {
        return alertRepository.findByResolvedAtIsNullOrderByTriggeredAtDesc().stream()
                .map(alertMapper::toDto).toList();
    }

    @Transactional
    public void resolve(Long id) {
        Alert alert = alertRepository.findById(id).orElseThrow(() -> NotFoundException.of("Alert", id));
        alert.setResolvedAt(Instant.now());
        alertRepository.save(alert);
    }
}
