package com.aqss.backend.mqtt;

/** Canonical MQTT topics (io_contract §8). */
public final class MqttTopics {

    public static final String DEVICE_PREFIX = "aqss/devices/";
    public static final String COMMAND_SUFFIX = "/command";

    private MqttTopics() {
    }

    public static String command(String deviceId) {
        return DEVICE_PREFIX + deviceId + COMMAND_SUFFIX;
    }
}
