#include "mqtt_network.h"

#include <ArduinoJson.h>
#include <ArduinoMqttClient.h>
#include <WiFi.h>
#include <math.h>
#include <time.h>

#include "config.h"
#include "config_store.h"
#include "provisioning.h"
#include "sensor_data.h"
#include "system_utils.h"

// io_contract.md §1–§4
static const char kTopicTelemetry[] = "aqss/devices/" AQSS_DEVICE_ID "/telemetry";
static const char kTopicCommand[] = "aqss/devices/" AQSS_DEVICE_ID "/command";
static const char kTopicState[] = "aqss/devices/" AQSS_DEVICE_ID "/actuator/state";
static const char kTopicStatus[] = "aqss/devices/" AQSS_DEVICE_ID "/status";

#if MQTT_USE_TLS
#include <WiFiClientSecure.h>

#include "tls_certs.h"

static WiFiClientSecure s_net;
#else
static WiFiClient s_net;
#endif
static MqttClient s_mqtt(&s_net);

typedef struct {
    char space_id[24];
    uint32_t timestamp;
    float pm25;
    float co2;
    float temperature;
    float humidity;
    bool pm25_valid;
    bool co2_valid;
    bool pm25_saturated;
    bool dust_fault;
    int rssi;
    uint32_t uptime_s;
    uint32_t free_heap;
    uint32_t min_free_heap;
} TelemetrySample;

// Offline buffer (§10.3): keep the last 5 minutes of readings and back-fill them
// with their original timestamps once the broker is reachable again.
static TelemetrySample s_offline[OFFLINE_BUFFER_SLOTS];
static uint16_t s_offlineHead = 0;
static uint16_t s_offlineCount = 0;

static NetConfig s_netCfg;
static bool s_ntpRequested = false;
static bool s_forceNtpSync = false;
static uint32_t s_lastTelemetryWarnMs = 0;

static bool timeIsValid() {
    return (uint32_t)time(nullptr) > TIME_VALID_EPOCH;
}

static bool publishPayload(const char* topic, const char* payload, bool retain, uint8_t qos) {
    const size_t len = strlen(payload);
    if (!s_mqtt.beginMessage(topic, (unsigned long)len, retain, qos)) {
        return false;
    }
    s_mqtt.write((const uint8_t*)payload, len);
    return s_mqtt.endMessage() == 1;
}

static void serializeSample(const TelemetrySample& s, char* out, size_t len) {
    JsonDocument doc;
    doc["device_id"] = AQSS_DEVICE_ID;
    doc["space_id"] = s.space_id;
    char code[PAIRING_CODE_LEN + 1];
    pairingCode(code, sizeof(code));
    doc["pairing_code"] = code;
    doc["timestamp"] = s.timestamp;

    // A sensor with no reading yet is absent, not 0.0: 0 is outside the acceptance window and the
    // backend would discard the whole sample, losing the PM2.5 that did read.
    JsonObject sensors = doc["sensors"].to<JsonObject>();
    if (s.pm25_valid) {
        sensors["pm25"] = roundf(s.pm25 * 10.0f) / 10.0f;
    } else {
        sensors["pm25"] = nullptr;
    }
    if (s.co2_valid) {
        sensors["co2"] = roundf(s.co2 * 10.0f) / 10.0f;
        sensors["temperature"] = roundf(s.temperature * 10.0f) / 10.0f;
        sensors["humidity"] = roundf(s.humidity * 10.0f) / 10.0f;
    } else {
        sensors["co2"] = nullptr;
        sensors["temperature"] = nullptr;
        sensors["humidity"] = nullptr;
    }

    JsonObject system = doc["system"].to<JsonObject>();
    system["wifi_rssi"] = s.rssi;
    system["uptime_sec"] = s.uptime_s;
    system["free_heap"] = s.free_heap;
    system["min_free_heap"] = s.min_free_heap;
    system["pm25_saturated"] = s.pm25_saturated;
    system["dust_fault"] = s.dust_fault;

    serializeJson(doc, out, len);
}

static void bufferOffline(const TelemetrySample& sample) {
    const uint16_t slot = (uint16_t)((s_offlineHead + s_offlineCount) % OFFLINE_BUFFER_SLOTS);
    s_offline[slot] = sample;
    if (s_offlineCount < OFFLINE_BUFFER_SLOTS) {
        s_offlineCount++;
    } else {
        s_offlineHead = (uint16_t)((s_offlineHead + 1) % OFFLINE_BUFFER_SLOTS);
    }
}

static void publishStatus(const char* status) {
    char payload[160];
    char code[PAIRING_CODE_LEN + 1];
    pairingCode(code, sizeof(code));
    const uint32_t ts = timeIsValid() ? (uint32_t)time(nullptr) : 0;
    snprintf(payload, sizeof(payload),
             "{\"device_id\":\"%s\",\"pairing_code\":\"%s\",\"status\":\"%s\",\"timestamp\":%lu}",
             AQSS_DEVICE_ID, code, status, (unsigned long)ts);
    if (!publishPayload(kTopicStatus, payload, true, 1)) {
        AQSS_LOG("mqtt: status publish failed");
    }
}

/** Snapshot the shared state into the wire shape used by both the live and the buffered path. */
static TelemetrySample currentSample() {
    const SensorData_t d = dataSnapshot();
    TelemetrySample sample;
    memset(&sample, 0, sizeof(sample));
    strlcpy(sample.space_id, d.space_id, sizeof(sample.space_id));
    sample.timestamp = timeIsValid() ? (uint32_t)time(nullptr) : 0;
    sample.pm25 = d.pm25_ugm3;
    sample.co2 = d.co2_ppm;
    sample.temperature = d.temperature_c;
    sample.humidity = d.humidity_pct;
    sample.pm25_valid = d.dust_ready;
    sample.co2_valid = d.scd40_ready;
    sample.pm25_saturated = d.dust_saturated;
    sample.dust_fault = d.dust_fault;
    sample.rssi = d.wifi_rssi;
    sample.uptime_s = millis() / 1000UL;
    sample.free_heap = ESP.getFreeHeap();
    sample.min_free_heap = d.min_free_heap;
    return sample;
}

/** Called while the broker is unreachable so the gap is back-filled once it returns. */
static void bufferCurrentReading() {
    if (!timeIsValid()) {
        return;
    }
    bufferOffline(currentSample());
}

static void publishTelemetry() {
    if (!timeIsValid()) {
        const uint32_t now = millis();
        if (now - s_lastTelemetryWarnMs > 30000UL) {
            s_lastTelemetryWarnMs = now;
            AQSS_LOG("mqtt: telemetry paused, waiting for NTP time");
        }
        return;
    }

    const TelemetrySample sample = currentSample();

    char payload[MQTT_BUFFER_SIZE];
    serializeSample(sample, payload, sizeof(payload));

    if (!publishPayload(kTopicTelemetry, payload, false, 1)) {
        AQSS_LOG("mqtt: telemetry publish failed, buffering offline");
        bufferOffline(sample);
    }
}

static void publishActuatorState() {
    const SensorData_t d = dataSnapshot();

    JsonDocument doc;
    doc["device_id"] = AQSS_DEVICE_ID;
    doc["timestamp"] = d.timestamp;
    doc["state"] = d.fan_state ? "ON" : "OFF";
    doc["level"] = d.fan_level;
    doc["duty_pct"] = d.fan_duty_pct;
    doc["mode"] = d.fan_mode == FAN_MODE_AUTO ? "AUTO" : "MANUAL";
    doc["filter_runtime_h"] = roundf((d.filter_runtime_s / 3600.0f) * 10.0f) / 10.0f;

    char payload[256];
    serializeJson(doc, payload, sizeof(payload));
    if (!publishPayload(kTopicState, payload, false, 1)) {
        AQSS_LOG("mqtt: actuator state publish failed");
    }
}

static void flushOffline() {
    if (s_offlineCount == 0) {
        return;
    }
    char payload[MQTT_BUFFER_SIZE];
    serializeSample(s_offline[s_offlineHead], payload, sizeof(payload));
    if (!publishPayload(kTopicTelemetry, payload, false, 1)) {
        return;
    }
    s_offlineHead = (uint16_t)((s_offlineHead + 1) % OFFLINE_BUFFER_SLOTS);
    s_offlineCount--;
    if (s_offlineCount == 0) {
        AQSS_LOG("mqtt: offline buffer drained");
    }
}

static void handleCommand(const char* payload, size_t length) {
    JsonDocument doc;
    const DeserializationError err = deserializeJson(doc, payload, length);
    if (err) {
        AQSS_LOG("cmd: malformed JSON (%s)", err.c_str());
        return;
    }

    // V1 crashed on strcmp(nullptr, ...); always resolve the key with a fallback.
    const char* command = doc["command"] | "";
    if (command[0] == '\0') {
        AQSS_LOG("cmd: missing 'command' field");
        return;
    }
    JsonVariant params = doc["params"];

    if (strcmp(command, "set_actuator") == 0) {
        const char* state = params["state"] | "OFF";
        const char* mode = params["mode"] | "AUTO";
        int level = params["level"] | 0;
        if (level < 0) {
            level = 0;
        }
        if (level > 3) {
            level = 3;
        }
        const bool on = strcmp(state, "ON") == 0;
        // The backend decides whether this is an automatic or a manual override; forcing AUTO
        // here is what made a dashboard MANUAL last less than one telemetry period.
        const FanMode_t fanMode = (strcmp(mode, "MANUAL") == 0) ? FAN_MODE_MANUAL : FAN_MODE_AUTO;
        fanCmdSet(on, (uint8_t)level, fanMode, true);
        AQSS_LOG("cmd: set_actuator state=%s level=%d mode=%s", state, level, mode);

    } else if (strcmp(command, "display_warning") == 0) {
        const char* message = params["message"] | "";
        const char* severity = params["severity"] | "WARNING";
        dataLock();
        g_sensorData.warning_active = message[0] != '\0';
        strlcpy(g_sensorData.warning_msg, message, sizeof(g_sensorData.warning_msg));
        strlcpy(g_sensorData.warning_severity, severity, sizeof(g_sensorData.warning_severity));
        g_sensorData.warning_set_ms = millis();
        dataUnlock();
        AQSS_LOG("cmd: display_warning [%s] %s", severity, message);

    } else if (strcmp(command, "set_space") == 0) {
        const char* spaceId = params["space_id"] | "";
        if (spaceId[0] != '\0') {
            configSetSpaceId(spaceId);
            configSetPaired(true);
            dataLock();
            strlcpy(g_sensorData.space_id, spaceId, sizeof(g_sensorData.space_id));
            g_sensorData.paired = true;
            dataUnlock();
            AQSS_LOG("cmd: set_space %s (paired)", spaceId);
        }

    } else if (strcmp(command, "recalibrate_dust") == 0) {
        const SensorData_t d = dataSnapshot();
        float baseline = params["baseline_w0"] | 0.0f;
        if (baseline <= 0.0f) {
            baseline = d.dust_pulse_us;  // calibrate against the current clean-air pulse
        }
        if (baseline >= 800.0f && baseline <= 2500.0f) {
            configSetDustBaseline(baseline);
            dataLock();
            g_sensorData.dust_baseline_us = baseline;
            dataUnlock();
            AQSS_LOG("cmd: recalibrate_dust baseline=%.0fus", baseline);
        } else {
            AQSS_LOG("cmd: recalibrate_dust rejected baseline=%.0fus", baseline);
        }

    } else if (strcmp(command, "set_interval") == 0) {
        const uint32_t interval = params["interval_ms"] | TELEMETRY_INTERVAL_DEFAULT_MS;
        configSetTelemetryInterval(interval);
        AQSS_LOG("cmd: set_interval %lums", (unsigned long)configSnapshot().telemetry_interval_ms);

    } else if (strcmp(command, "reset_filter") == 0) {
        configSetFilterRuntime(0);
        configPersistFilterRuntime();
        dataLock();
        g_sensorData.filter_runtime_s = 0;
        dataUnlock();
        AQSS_LOG("cmd: reset_filter — fan hours counter cleared");

    } else if (strcmp(command, "force_ntp_sync") == 0) {
        s_forceNtpSync = true;
        AQSS_LOG("cmd: force_ntp_sync");

    } else if (strcmp(command, "reboot") == 0) {
        AQSS_LOG("cmd: reboot");
        vTaskDelay(pdMS_TO_TICKS(200));
        ESP.restart();

    } else {
        AQSS_LOG("cmd: unsupported '%s'", command);
    }
}

static void onMqttMessage(int messageSize) {
    const String topic = s_mqtt.messageTopic();

    static char payload[MQTT_BUFFER_SIZE];
    size_t length = 0;
    while (s_mqtt.available() && length < sizeof(payload) - 1) {
        payload[length++] = (char)s_mqtt.read();
    }
    payload[length] = '\0';
    while (s_mqtt.available()) {
        s_mqtt.read();  // drain anything larger than the buffer
    }

    if (topic == kTopicCommand) {
        handleCommand(payload, length);
    }
}

// Serial console: `setup` opens the portal, `forget` clears the credentials.
static void pollSerialCommands() {
    static char line[32];
    static size_t length = 0;
    while (Serial.available()) {
        const char c = (char)Serial.read();
        if (c == '\r' || c == '\n') {
            line[length] = '\0';
            if (strcmp(line, "setup") == 0) {
                AQSS_LOG("serial: opening the setup portal");
                provisioningRun(false);
            } else if (strcmp(line, "forget") == 0) {
                AQSS_LOG("serial: credentials cleared, rebooting");
                netClear();
                vTaskDelay(pdMS_TO_TICKS(300));
                ESP.restart();
            } else if (strcmp(line, "net") == 0) {
                NetConfig net = netConfig();
                AQSS_LOG("serial: ssid='%s' wifi_pass_len=%u", net.wifi_ssid, (unsigned)strlen(net.wifi_pass));
                AQSS_LOG("serial: broker='%s:%d' user='%s' pass_len=%u", MQTT_SERVER, MQTT_PORT,
                         MQTT_USER, (unsigned)strlen(net.mqtt_pass));
            } else if (strncmp(line, "cred ", 5) == 0) {
                // cred <password>  (the host/port/user are fixed in config.h)
                const char* pass = line + 5;
                if (*pass != '\0') {
                    NetConfig net = netConfig();
                    netSet(net.wifi_ssid, net.wifi_pass, pass);
                    AQSS_LOG("serial: broker password updated (%u chars), rebooting",
                             (unsigned)strlen(pass));
                    vTaskDelay(pdMS_TO_TICKS(300));
                    ESP.restart();
                } else {
                    AQSS_LOG("serial: usage: cred <password>");
                }
            } else if (strcmp(line, "reboot") == 0) {
                ESP.restart();
            } else if (length > 0) {
                AQSS_LOG("serial: unknown command '%s' (setup | forget | net | cred | reboot)", line);
            }
            length = 0;
        } else if (length < sizeof(line) - 1) {
            line[length++] = c;
        }
    }
}

static void wifiBegin() {
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(AQSS_DEVICE_ID);
    WiFi.setAutoReconnect(true);
    WiFi.begin(s_netCfg.wifi_ssid, s_netCfg.wifi_pass);
    AQSS_LOG("wifi: connecting to '%s'", s_netCfg.wifi_ssid);
}

static bool mqttConnect() {
    static uint32_t lastAttemptMs = 0;
    if ((uint32_t)(millis() - lastAttemptMs) < MQTT_RECONNECT_MS) {
        return false;
    }
    lastAttemptMs = millis();

    char clientId[40];
    snprintf(clientId, sizeof(clientId), "%s-%08lX", AQSS_DEVICE_ID,
             (unsigned long)(ESP.getEfuseMac() & 0xFFFFFFFFUL));

    s_mqtt.setId(clientId);
    s_mqtt.setUsernamePassword(MQTT_USER, s_netCfg.mqtt_pass);
    s_mqtt.setKeepAliveInterval(MQTT_KEEPALIVE_S * 1000UL);
    s_mqtt.setConnectionTimeout(MQTT_SOCKET_TIMEOUT_S * 1000UL);
    s_mqtt.setTxPayloadSize(MQTT_BUFFER_SIZE);
    s_mqtt.onMessage(onMqttMessage);

    // LWT: the broker announces OFFLINE if this node dies ungracefully (§3).
    char will[128];
    snprintf(will, sizeof(will), "{\"device_id\":\"%s\",\"status\":\"OFFLINE\"}", AQSS_DEVICE_ID);
    s_mqtt.beginWill(kTopicStatus, (unsigned short)strlen(will), true, 1);
    s_mqtt.write((const uint8_t*)will, strlen(will));
    if (!s_mqtt.endWill()) {
        AQSS_LOG("mqtt: endWill failed");
        return false;
    }

    // MqttClient::connect() returns 1 on success (0 on failure); the reason is in connectError().
    if (s_mqtt.connect(MQTT_SERVER, MQTT_PORT) != 1) {
        AQSS_LOG("mqtt: connect to %s:%d failed (%d)", MQTT_SERVER, MQTT_PORT, s_mqtt.connectError());
        return false;
    }
    if (s_mqtt.subscribe(kTopicCommand, 1) != 1) {
        AQSS_LOG("mqtt: subscribe to %s failed", kTopicCommand);
    }
    // The status carries the pairing code; the dashboard needs it, so publish it once NTP is
    // valid (a timestamp of 0 would be stored as 1970 and read as "never seen").
    if (timeIsValid()) {
        publishStatus("ONLINE");
    }
    AQSS_LOG("mqtt: connected to %s:%d", MQTT_SERVER, MQTT_PORT);
    return true;
}

void Task_MQTT_Network(void* pvParam) {
    aqssWdtWatchCurrentTask();

    s_netCfg = netConfig();
    if (!netConfigured() || g_forceProvisioning) {
        AQSS_LOG("%s -> setup portal",
                 g_forceProvisioning ? "button held" : "no credentials");
        provisioningRun(false);   // manual setup: AP stays until saved
    }

    {
        char code[PAIRING_CODE_LEN + 1];
        pairingCode(code, sizeof(code));
        AQSS_LOG("pairing: code %s — enter it in the dashboard to link this node to a room", code);
    }

#if MQTT_USE_TLS
    s_net.setCACert(MQTT_ROOT_CA);    // pinned root — rejects a MITM'd broker
    AQSS_LOG("mqtt: TLS enabled, target mqtts://%s:%d", MQTT_SERVER, MQTT_PORT);
#else
    AQSS_LOG("mqtt: plaintext, target tcp://%s:%d", MQTT_SERVER, MQTT_PORT);
#endif

    // No link -> setup mode until the station joins (softAP stays up the whole time).
    wifiBegin();
    const uint32_t joinStartMs = millis();
    while (!WiFi.isConnected() && (uint32_t)(millis() - joinStartMs) < STA_CONNECT_TIMEOUT_MS) {
        aqssWdtFeed();
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    if (!WiFi.isConnected()) {
        AQSS_LOG("wifi: no link to '%s' -> setup portal", s_netCfg.wifi_ssid);
        provisioningRun(true);      // returns once the station connects
        s_netCfg = netConfig();
    }
    AQSS_LOG("wifi: connected as %s", WiFi.localIP().toString().c_str());

    uint32_t lastWifiRetryMs = 0;
    uint32_t wifiDownSinceMs = millis();
    uint32_t lastTelemetryMs = 0;
    uint32_t lastStateMs = 0;
    uint32_t lastNtpMs = 0;
    bool lastFanState = false;
    uint8_t lastFanLevel = 0;
    uint8_t lastFanMode = 0xFF;   // 0xFF forces the first actuator-state publish
    bool wasTimeValid = false;

    for (;;) {
        aqssWdtFeed();
        pollSerialCommands();
        const uint32_t now = millis();

        if (!WiFi.isConnected()) {
            if ((uint32_t)(now - wifiDownSinceMs) > WIFI_LOST_SETUP_MS) {
                AQSS_LOG("wifi: down for %lus -> setup portal", WIFI_LOST_SETUP_MS / 1000);
                provisioningRun(true);   // back to setup until the station joins again
                s_netCfg = netConfig();
                continue;
            }
            if ((uint32_t)(now - lastWifiRetryMs) > 10000UL) {
                lastWifiRetryMs = now;
                AQSS_LOG("wifi: not connected (status %d), retrying", (int)WiFi.status());
                WiFi.reconnect();
            }
            dataLock();
            g_sensorData.wifi_rssi = 0;
            g_sensorData.wifi_connected = false;
            g_sensorData.mqtt_connected = false;
            dataUnlock();
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }

        // NTP: initial sync, hourly resync, or on demand via `force_ntp_sync`.
        if (!s_ntpRequested || s_forceNtpSync || (uint32_t)(now - lastNtpMs) >= NTP_RESYNC_MS) {
            s_ntpRequested = true;
            s_forceNtpSync = false;
            lastNtpMs = now;
            configTime(NTP_GMT_OFFSET_S, 0, NTP_SERVER_1, NTP_SERVER_2);
            AQSS_LOG("ntp: sync requested (ip=%s rssi=%d)", WiFi.localIP().toString().c_str(), WiFi.RSSI());
        }

        // The first ONLINE was suppressed because time was not valid yet; publish it now that it
        // is, so the backend gets a dated liveness message rather than 1970.
        if (!wasTimeValid && timeIsValid()) {
            wasTimeValid = true;
            if (s_mqtt.connected()) {
                publishStatus("ONLINE");
                AQSS_LOG("ntp: time valid, status ONLINE published");
            }
        }

        if (!s_mqtt.connected()) {
            // Still sample while offline: otherwise the buffer only ever fills when a publish
            // fails on a live connection, which is the one case that rarely happens.
            const RuntimeConfig offlineCfg = configSnapshot();
            if ((uint32_t)(now - lastTelemetryMs) >= offlineCfg.telemetry_interval_ms) {
                lastTelemetryMs = now;
                bufferCurrentReading();
            }
            dataLock();
            g_sensorData.mqtt_connected = false;
            dataUnlock();
            mqttConnect();
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }

        s_mqtt.poll();

        const RuntimeConfig cfg = configSnapshot();
        if ((uint32_t)(now - lastTelemetryMs) >= cfg.telemetry_interval_ms) {
            lastTelemetryMs = now;
            publishTelemetry();
        }

        const SensorData_t d = dataSnapshot();
        // Publish on any change of state/level/mode — not just on the ON/OFF edge, otherwise a
        // level change while the fan is OFF never reaches the backend — plus a heartbeat while ON.
        if (d.fan_state != lastFanState || d.fan_level != lastFanLevel || d.fan_mode != lastFanMode ||
            (d.fan_state && (uint32_t)(now - lastStateMs) >= FAN_STATE_PUBLISH_MS)) {
            lastFanState = d.fan_state;
            lastFanLevel = d.fan_level;
            lastFanMode = d.fan_mode;
            lastStateMs = now;
            publishActuatorState();
        }

        flushOffline();

        wifiDownSinceMs = millis();
        dataLock();
        g_sensorData.wifi_rssi = WiFi.RSSI();
        g_sensorData.wifi_connected = true;
        g_sensorData.mqtt_connected = s_mqtt.connected();
        g_sensorData.timestamp = timeIsValid() ? (uint32_t)time(nullptr) : 0;
        g_sensorData.min_free_heap = ESP.getMinFreeHeap();
        dataUnlock();

        vTaskDelay(pdMS_TO_TICKS(50));
    }
}
