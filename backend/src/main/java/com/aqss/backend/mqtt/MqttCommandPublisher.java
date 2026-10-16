package com.aqss.backend.mqtt;

import com.aqss.backend.config.MqttIntegrationConfig;
import com.aqss.backend.config.MqttProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Publishes server → device commands (io_contract §4) on the outbound MQTT channel. */
@Component
@Slf4j
public class MqttCommandPublisher {

    private final MessageChannel outboundChannel;
    private final MqttProperties properties;
    private final ObjectMapper objectMapper;

    public MqttCommandPublisher(@Qualifier(MqttIntegrationConfig.MQTT_OUTBOUND_CHANNEL) MessageChannel outboundChannel,
                                MqttProperties properties,
                                ObjectMapper objectMapper) {
        this.outboundChannel = outboundChannel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publish(String deviceId, String command, Map<String, Object> params) {
        try {
            String payload = objectMapper.writeValueAsString(new CommandPayload(command, params));
            Message<String> message = MessageBuilder.withPayload(payload)
                    .setHeader(MqttHeaders.TOPIC, MqttTopics.command(deviceId))
                    .setHeader(MqttHeaders.QOS, properties.qos())
                    .build();
            outboundChannel.send(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize MQTT command " + command, e);
        } catch (Exception e) {
            // No broker / MQTT disabled (mqtt.enabled=false): keep the REST + control loop working.
            log.warn("Could not publish MQTT command {} to {}: {}", command, deviceId, e.getMessage());
        }
    }

    /** {@code {"command": "...", "params": {...}}} */
    public record CommandPayload(String command, Map<String, Object> params) {
    }
}
