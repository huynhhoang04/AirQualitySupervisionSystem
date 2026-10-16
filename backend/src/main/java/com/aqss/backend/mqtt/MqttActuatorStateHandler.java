package com.aqss.backend.mqtt;

import com.aqss.backend.dto.mqtt.ActuatorStateMessage;
import com.aqss.backend.service.ActuatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MqttActuatorStateHandler {

    private final ActuatorService actuatorService;

    public void handle(ActuatorStateMessage message) {
        if (message.deviceId() == null) {
            return;
        }
        actuatorService.updateStateFromDevice(message);
    }
}
