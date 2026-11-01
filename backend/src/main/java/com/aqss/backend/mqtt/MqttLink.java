package com.aqss.backend.mqtt;

import com.aqss.backend.config.AqssSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The only MQTT code: one connection, one subscription set, one publish call.
 * Inbound payloads become Spring events; the services subscribe with {@code @EventListener}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttLink implements MqttCallbackExtended {

    private static final String TOPIC_FILTER = "aqss/devices/+/+";
    private static final String TOPIC_FILTER_STATE = "aqss/devices/+/actuator/state";

    private final AqssSettings settings;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    private MqttClient client;

    @PostConstruct
    void start() {
        // Never block bean creation on the broker: Render's health check would time out while a
        // slow or unreachable broker is being dialled.
        Thread thread = new Thread(this::connect, "mqtt-init");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void connect() {
        if (client != null && client.isConnected()) {
            return;
        }
        try {
            if (client == null) {
                client = new MqttClient(settings.mqtt().brokerUrl(), settings.mqtt().clientId(), null);
                client.setCallback(this);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setKeepAliveInterval(30);
            options.setConnectionTimeout(10);
            if (settings.mqtt().username() != null && !settings.mqtt().username().isBlank()) {
                options.setUserName(settings.mqtt().username());
                options.setPassword(settings.mqtt().password().toCharArray());
            }
            client.connect(options);
            client.subscribe(TOPIC_FILTER, settings.mqtt().qos());
            client.subscribe(TOPIC_FILTER_STATE, settings.mqtt().qos());
            log.info("MQTT connected to {} (telemetry + status + actuator state)", settings.mqtt().brokerUrl());
        } catch (Exception e) {
            log.warn("MQTT unavailable ({}): {}", settings.mqtt().brokerUrl(), e.getMessage());
        }
    }

    /** Re-arm the connection if the first attempt lost the race with the platform network. */
    @Scheduled(fixedDelay = 30_000)
    public void ensureConnected() {
        connect();
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(TOPIC_FILTER, settings.mqtt().qos());
            client.subscribe(TOPIC_FILTER_STATE, settings.mqtt().qos());
            log.info("MQTT {} to {}", reconnect ? "reconnected" : "connected", serverURI);
        } catch (Exception e) {
            log.warn("Could not resubscribe: {}", e.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            if (topic.endsWith("/telemetry")) {
                events.publishEvent(mapper.readValue(payload, TelemetryEvent.class));
            } else if (topic.endsWith("/status")) {
                events.publishEvent(mapper.readValue(payload, StatusEvent.class));
            } else if (topic.endsWith("/actuator/state")) {
                events.publishEvent(mapper.readValue(payload, ActuatorStateEvent.class));
            }
        } catch (Exception e) {
            log.warn("Dropped MQTT message on {} ({}): {}", topic, payload, e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1 acknowledgement only; nothing to do.
    }

    /** {@code {"command": "...", "params": {...}}} — see io_contract.md §4. */
    public void publishCommand(String deviceId, String command, Map<String, Object> params) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "command", command,
                    "params", params == null ? Map.of() : params));
            MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            message.setQos(settings.mqtt().qos());
            client.publish("aqss/devices/" + deviceId + "/command", message);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish MQTT command " + command + ": " + e.getMessage(), e);
        }
    }

    // ── Inbound payloads (io_contract §1–§3) ─────────────────────────────────
    public record Sensors(Float pm25, Float co2, Float temperature, Float humidity) {
    }

    public record SystemInfo(Integer wifi_rssi, Long uptime_sec, Long free_heap, Long min_free_heap) {
    }

    public record TelemetryEvent(String deviceId, String spaceId, Long timestamp,
                                 Sensors sensors, SystemInfo system) {
    }

    public record StatusEvent(String deviceId, String status, Long timestamp) {
    }

    public record ActuatorStateEvent(String deviceId, String state, Integer level,
                                     Integer dutyPct, String mode, Float filterRuntimeH) {
    }
}
