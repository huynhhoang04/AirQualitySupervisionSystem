#include "config_store.h"

#include <Preferences.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#include "config.h"

static const char* kNamespace = "aqss";

static Preferences s_prefs;
static RuntimeConfig s_cfg;
static NetConfig s_net;
static SemaphoreHandle_t s_cfgMutex = nullptr;

static void cfgLock() {
    xSemaphoreTake(s_cfgMutex, portMAX_DELAY);
}

static void cfgUnlock() {
    xSemaphoreGive(s_cfgMutex);
}

void configBegin() {
    if (s_cfgMutex == nullptr) {
        s_cfgMutex = xSemaphoreCreateMutex();
    }

    s_prefs.begin(kNamespace, false);

    String spaceId = s_prefs.getString("space_id", AQSS_DEFAULT_SPACE_ID);
    strlcpy(s_cfg.space_id, spaceId.c_str(), sizeof(s_cfg.space_id));
    s_cfg.dust_baseline_us = s_prefs.getFloat("w0", DUST_ZERO_PULSE_US);
    s_cfg.telemetry_interval_ms = s_prefs.getUInt("interval", TELEMETRY_INTERVAL_DEFAULT_MS);
    s_cfg.filter_runtime_s = s_prefs.getUInt("filter_s", 0);

    // Network credentials: NVS first, compile-time defaults (include/secrets.h) as a fallback.
    strlcpy(s_net.wifi_ssid, s_prefs.getString("wifi_ssid", WIFI_SSID).c_str(), sizeof(s_net.wifi_ssid));
    strlcpy(s_net.wifi_pass, s_prefs.getString("wifi_pass", WIFI_PASSWORD).c_str(), sizeof(s_net.wifi_pass));
    strlcpy(s_net.mqtt_pass, s_prefs.getString("mqtt_pass", MQTT_PASSWORD).c_str(), sizeof(s_net.mqtt_pass));

    s_prefs.end();

    if (s_cfg.telemetry_interval_ms < TELEMETRY_INTERVAL_MIN_MS ||
        s_cfg.telemetry_interval_ms > TELEMETRY_INTERVAL_MAX_MS) {
        s_cfg.telemetry_interval_ms = TELEMETRY_INTERVAL_DEFAULT_MS;
    }
    if (s_cfg.dust_baseline_us < 800.0f || s_cfg.dust_baseline_us > 2500.0f) {
        s_cfg.dust_baseline_us = DUST_ZERO_PULSE_US;
    }
}

RuntimeConfig configSnapshot() {
    cfgLock();
    RuntimeConfig copy = s_cfg;
    cfgUnlock();
    return copy;
}

void configSetSpaceId(const char* space_id) {
    if (space_id == nullptr || space_id[0] == '\0') {
        return;
    }
    cfgLock();
    strlcpy(s_cfg.space_id, space_id, sizeof(s_cfg.space_id));
    cfgUnlock();

    s_prefs.begin(kNamespace, false);
    s_prefs.putString("space_id", space_id);
    s_prefs.end();
}

void configSetDustBaseline(float baseline_us) {
    if (baseline_us < 800.0f || baseline_us > 2500.0f) {
        return;
    }
    cfgLock();
    s_cfg.dust_baseline_us = baseline_us;
    cfgUnlock();

    s_prefs.begin(kNamespace, false);
    s_prefs.putFloat("w0", baseline_us);
    s_prefs.end();
}

void configSetTelemetryInterval(uint32_t interval_ms) {
    if (interval_ms < TELEMETRY_INTERVAL_MIN_MS) {
        interval_ms = TELEMETRY_INTERVAL_MIN_MS;
    }
    if (interval_ms > TELEMETRY_INTERVAL_MAX_MS) {
        interval_ms = TELEMETRY_INTERVAL_MAX_MS;
    }
    cfgLock();
    s_cfg.telemetry_interval_ms = interval_ms;
    cfgUnlock();

    s_prefs.begin(kNamespace, false);
    s_prefs.putUInt("interval", interval_ms);
    s_prefs.end();
}

void configSetFilterRuntime(uint32_t seconds) {
    cfgLock();
    s_cfg.filter_runtime_s = seconds;
    cfgUnlock();
}

void configPersistFilterRuntime() {
    uint32_t seconds;
    cfgLock();
    seconds = s_cfg.filter_runtime_s;
    cfgUnlock();

    s_prefs.begin(kNamespace, false);
    s_prefs.putUInt("filter_s", seconds);
    s_prefs.end();
}

NetConfig netConfig() {
    cfgLock();
    NetConfig copy = s_net;
    cfgUnlock();
    return copy;
}

bool netConfigured() {
    cfgLock();
    const bool ok = s_net.wifi_ssid[0] != '\0';
    cfgUnlock();
    return ok;
}

void netSet(const char* ssid, const char* wifiPass, const char* mqttPass) {
    cfgLock();
    strlcpy(s_net.wifi_ssid, ssid ? ssid : "", sizeof(s_net.wifi_ssid));
    strlcpy(s_net.wifi_pass, wifiPass ? wifiPass : "", sizeof(s_net.wifi_pass));
    strlcpy(s_net.mqtt_pass, mqttPass ? mqttPass : "", sizeof(s_net.mqtt_pass));
    cfgUnlock();

    s_prefs.begin(kNamespace, false);
    s_prefs.putString("wifi_ssid", s_net.wifi_ssid);
    s_prefs.putString("wifi_pass", s_net.wifi_pass);
    s_prefs.putString("mqtt_pass", s_net.mqtt_pass);
    s_prefs.end();
}

void netClear() {
    cfgLock();
    memset(&s_net, 0, sizeof(s_net));
    cfgUnlock();

    s_prefs.begin(kNamespace, false);
    s_prefs.remove("wifi_ssid");
    s_prefs.remove("wifi_pass");
    s_prefs.remove("mqtt_pass");
    s_prefs.end();
}
