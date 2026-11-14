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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only MQTT code: one connection, one subscription set, one publish call.
 * Inbound payloads become Spring events; the services subscribe with {@code @EventListener}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttLink implements MqttCallbackExtended {

    private static final String TELEMETRY = "aqss/devices/+/telemetry";
    private static final String STATUS = "aqss/devices/+/status";
    private static final String ACTUATOR_STATE = "aqss/devices/+/actuator/state";

    private final AqssSettings settings;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    private volatile MqttClient client;

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
            subscribeAll();
            log.info("MQTT connected to {} (subscribed for telemetry, status, actuator state)",
                    settings.mqtt().brokerUrl());
        } catch (Exception e) {
            log.warn("MQTT unavailable ({}): {}", settings.mqtt().brokerUrl(), e.getMessage());
        }
    }

    private void subscribeAll() throws Exception {
        client.subscribe(TELEMETRY, settings.mqtt().qos());
        client.subscribe(STATUS, settings.mqtt().qos());
        client.subscribe(ACTUATOR_STATE, settings.mqtt().qos());
    }

    /** Re-arm the connection if the first attempt lost the race with the platform network. */
    @Scheduled(fixedDelay = 30_000)
    public void ensureConnected() {
        connect();
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            subscribeAll();
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
        String topicDeviceId = topicDeviceId(topic);
        try {
            if (topic.endsWith("/actuator/state")) {
                events.publishEvent(mapper.readValue(payload, ActuatorStateEvent.class));
            } else if (topic.endsWith("/telemetry")) {
                TelemetryEvent event = mapper.readValue(payload, TelemetryEvent.class);
                // The topic identity and the payload identity must agree; otherwise one node could
                // write under another node's name.
                if (topicDeviceId != null && event.deviceId() != null
                        && !topicDeviceId.equals(event.deviceId())) {
                    log.warn("Dropped telemetry: topic device {} != payload device {}",
                            topicDeviceId, event.deviceId());
                    return;
                }
                events.publishEvent(event);
            } else if (topic.endsWith("/status")) {
                events.publishEvent(mapper.readValue(payload, StatusEvent.class));
            }
        } catch (Exception e) {
            log.warn("Dropped MQTT message on {} ({}): {}", topic, payload, e.getMessage());
        }
    }

    /** {@code aqss/devices/{id}/...} → {@code id}, or null when the topic does not match. */
    private static String topicDeviceId(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 3 ? parts[2] : null;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1 acknowledgement only; nothing to do.
    }

    /**
     * {@code {"command": "...", "params": {...}}} — see io_contract.md §5.
     *
     * <p>Best-effort: a broker that is down must not break the REST API or the control loop. The
     * caller that cares (an explicit user command) checks the return value.
     */
    public boolean publishCommand(String deviceId, String command, Map<String, Object> params) {
        MqttClient current = client;
        if (current == null || !current.isConnected()) {
            log.warn("Not connected — dropped {} for {}", command, deviceId);
            return false;
        }
        try {
            // LinkedHashMap, not Map.of: Map.of rejects null values and one null param would turn
            // the whole command into an NPE on the control-loop thread.
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("command", command);
            envelope.put("params", params == null ? Map.of() : params);

            MqttMessage message = new MqttMessage(mapper.writeValueAsBytes(envelope));
            message.setQos(settings.mqtt().qos());
            current.publish("aqss/devices/" + deviceId + "/command", message);
            return true;
        } catch (Exception e) {
            log.warn("Cannot publish {} to {}: {}", command, deviceId, e.getMessage());
            return false;
        }
    }

    // ── Inbound payloads (io_contract §2–§4) ─────────────────────────────────
    public record Sensors(Float pm25, Float co2, Float temperature, Float humidity) {
    }

    public record SystemInfo(Integer wifi_rssi, Long uptime_sec, Long free_heap, Long min_free_heap,
                             Boolean pm25_saturated, Boolean dust_fault) {
    }

    public record TelemetryEvent(String deviceId, String spaceId, String pairingCode, Long timestamp,
                                 Sensors sensors, SystemInfo system) {
    }

    public record StatusEvent(String deviceId, String pairingCode, String status, Long timestamp) {
    }

    public record ActuatorStateEvent(String deviceId, Long timestamp, String state, Integer level,
                                     Integer dutyPct, String mode, Float filterRuntimeH) {
    }
}
