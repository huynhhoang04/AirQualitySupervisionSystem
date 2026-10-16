package com.aqss.backend.mqtt;

import com.aqss.backend.dto.mqtt.TelemetryMessage;
import com.aqss.backend.service.TelemetryIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MqttTelemetryHandler {

    private final TelemetryIngestionService ingestionService;

    public void handle(TelemetryMessage message) {
        if (message.deviceId() == null || message.sensors() == null) {
            log.warn("Dropping telemetry without device_id or sensors");
            return;
        }
        ingestionService.ingest(message);
    }
}
