package com.aqss.backend.mqtt;

import com.aqss.backend.dto.mqtt.ActuatorStateMessage;
import com.aqss.backend.dto.mqtt.StatusMessage;
import com.aqss.backend.dto.mqtt.TelemetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MqttInboundHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MqttTelemetryHandler telemetryHandler = mock(MqttTelemetryHandler.class);
    private final MqttStatusHandler statusHandler = mock(MqttStatusHandler.class);
    private final MqttActuatorStateHandler actuatorStateHandler = mock(MqttActuatorStateHandler.class);

    private final MqttInboundHandler handler =
            new MqttInboundHandler(objectMapper, telemetryHandler, statusHandler, actuatorStateHandler);

    private static Message<String> message(String topic, String payload) {
        return MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();
    }

    @Test
    @DisplayName("Telemetry topics are routed to the telemetry handler")
    void should_RouteToTelemetryHandler_When_TopicIsTelemetry() {
        String payload = """
                {"device_id":"AQSS_ESP32S3_01","space_id":"SP_201","timestamp":1724217600,
                 "sensors":{"pm25":35.4,"co2":620.0,"temperature":29.5,"humidity":68.2},
                 "system":{"wifi_rssi":-65,"uptime_sec":3600,"free_heap":245760,"min_free_heap":185000}}
                """;

        handler.handleMessage(message("aqss/devices/AQSS_ESP32S3_01/telemetry", payload));

        verify(telemetryHandler).handle(any(TelemetryMessage.class));
        verify(statusHandler, never()).handle(any());
        verify(actuatorStateHandler, never()).handle(any());
    }

    @Test
    @DisplayName("Status and actuator-state topics are routed to their own handlers")
    void should_RouteByTopicSuffix_When_StatusOrActuatorState() {
        handler.handleMessage(message("aqss/devices/AQSS_ESP32S3_01/status",
                "{\"device_id\":\"AQSS_ESP32S3_01\",\"status\":\"ONLINE\",\"timestamp\":1724217600}"));
        handler.handleMessage(message("aqss/devices/AQSS_ESP32S3_01/actuator/state",
                "{\"device_id\":\"AQSS_ESP32S3_01\",\"state\":\"ON\",\"level\":2,\"duty_pct\":60,\"mode\":\"AUTO\"}"));

        verify(statusHandler).handle(any(StatusMessage.class));
        verify(actuatorStateHandler).handle(any(ActuatorStateMessage.class));
    }

    @Test
    @DisplayName("A malformed payload is dead-lettered instead of crashing the flow")
    void should_DeadLetter_When_PayloadIsMalformed() {
        handler.handleMessage(message("aqss/devices/AQSS_ESP32S3_01/telemetry", "{not json"));

        verify(telemetryHandler, never()).handle(any());
    }
}
