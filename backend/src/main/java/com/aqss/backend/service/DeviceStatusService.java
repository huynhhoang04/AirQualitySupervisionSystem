package com.aqss.backend.service;

import com.aqss.backend.dto.mqtt.StatusMessage;
import com.aqss.backend.entity.AlertType;
import com.aqss.backend.entity.Severity;
import com.aqss.backend.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Tracks node liveness from the retained {@code status} topic / LWT. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceStatusService {

    public static final String TOPIC_DEVICE_STATUS = "/topic/device-status";

    private final DeviceRepository deviceRepository;
    private final AlertService alertService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void updateStatus(String deviceId, boolean online, Long timestamp) {
        Instant seenAt = timestamp != null ? Instant.ofEpochSecond(timestamp) : Instant.now();
        deviceRepository.findById(deviceId).ifPresentOrElse(device -> {
            device.setLastSeenAt(seenAt);
            deviceRepository.save(device);
        }, () -> log.warn("Status for unknown device {}", deviceId));

        if (!online) {
            alertService.raise(null, deviceId, AlertType.SENSOR_OFFLINE, Severity.CRITICAL,
                    "Node reported OFFLINE (LWT) — no telemetry expected from " + deviceId);
        }
        String status = online ? "ONLINE" : "OFFLINE";
        messagingTemplate.convertAndSend(TOPIC_DEVICE_STATUS, new StatusMessage(deviceId, status, seenAt.getEpochSecond()));
        log.info("Device {} is {}", deviceId, status);
    }
}
