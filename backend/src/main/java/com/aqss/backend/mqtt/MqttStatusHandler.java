package com.aqss.backend.mqtt;

import com.aqss.backend.dto.mqtt.StatusMessage;
import com.aqss.backend.service.DeviceStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MqttStatusHandler {

    private final DeviceStatusService deviceStatusService;

    public void handle(StatusMessage message) {
        if (message.deviceId() == null) {
            return;
        }
        deviceStatusService.updateStatus(message.deviceId(), message.isOnline(), message.timestamp());
    }
}
