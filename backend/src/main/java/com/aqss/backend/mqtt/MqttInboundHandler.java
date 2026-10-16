package com.aqss.backend.mqtt;

import com.aqss.backend.dto.mqtt.ActuatorStateMessage;
import com.aqss.backend.dto.mqtt.StatusMessage;
import com.aqss.backend.dto.mqtt.TelemetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

/**
 * Single entry point for all inbound MQTT messages. Routes by topic suffix and dead-letters any
 * malformed payload instead of crashing the ingestion flow (V1 panicked on {@code strcmp(nullptr)}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttInboundHandler implements MessageHandler {

    private final ObjectMapper objectMapper;
    private final MqttTelemetryHandler telemetryHandler;
    private final MqttStatusHandler statusHandler;
    private final MqttActuatorStateHandler actuatorStateHandler;

    @Override
    public void handleMessage(Message<?> message) throws MessagingException {
        Object topicHeader = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        String topic = topicHeader == null ? "" : topicHeader.toString();
        String payload = String.valueOf(message.getPayload());

        try {
            if (topic.endsWith("/telemetry")) {
                telemetryHandler.handle(objectMapper.readValue(payload, TelemetryMessage.class));
            } else if (topic.endsWith("/actuator/state")) {
                actuatorStateHandler.handle(objectMapper.readValue(payload, ActuatorStateMessage.class));
            } else if (topic.endsWith("/status")) {
                statusHandler.handle(objectMapper.readValue(payload, StatusMessage.class));
            } else {
                log.debug("Ignoring message on unhandled topic {}", topic);
            }
        } catch (Exception e) {
            log.warn("Dead-letter: failed to handle MQTT message on topic {} ({}): {}", topic, payload, e.getMessage());
        }
    }
}
