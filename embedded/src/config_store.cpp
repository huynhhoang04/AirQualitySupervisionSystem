#include "config_store.h"

#include <Preferences.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#include "config.h"
#include "system_utils.h"

static const char* kNamespace = "aqss";

// 32 symbols: no I, O, 0 or 1 — the characters people mistype when reading a 0.96" OLED.
static const char kPairingAlphabet[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

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

    if (!s_prefs.begin(kNamespace, false)) {
        // A failed NVS open must not take the node down: every read below then falls back to the
        // compile-time default and the node runs with a reset configuration for this boot.
        AQSS_LOG("nvs: open failed — running on defaults this boot");
    }

    String spaceId = s_prefs.getString("space_id", AQSS_DEFAULT_SPACE_ID);
    strlcpy(s_cfg.space_id, spaceId.c_str(), sizeof(s_cfg.space_id));
    s_cfg.dust_baseline_us = s_prefs.getFloat("w0", DUST_ZERO_PULSE_US);
    s_cfg.telemetry_interval_ms = s_prefs.getUInt("interval", TELEMETRY_INTERVAL_DEFAULT_MS);
    s_cfg.filter_runtime_s = s_prefs.getUInt("filter_s", 0);
    s_cfg.paired = s_prefs.getBool("paired", false);

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

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot persist space_id");
        return;
    }
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

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot persist w0");
        return;
    }
    s_prefs.putFloat("w0", baseline_us);
    s_prefs.end();
}

/**
 * Stable per-chip code, e.g. "7K2M9X". 32 symbols × 6 chars ≈ 1.07 billion combinations; the
 * backend matches the newest node that reported the same code, so a collision is harmless in
 * practice and the operator sees the device's name next to it in the pair dialog.
 */
void pairingCode(char* out, size_t len) {
    if (out == nullptr || len == 0) {
        return;
    }
    uint32_t hash = (uint32_t)(ESP.getEfuseMac() & 0xFFFFFFFFULL) ^ 0x5A17C3E9UL;
    size_t written = 0;
    for (int i = 0; i < PAIRING_CODE_LEN && written < len - 1; i++) {
        out[written++] = kPairingAlphabet[hash & 0x1F];
        hash = (hash >> 5) ^ (hash * 2654435761UL);
        if (hash == 0) {
            hash = 0x9E3779B9UL;
        }
    }
    out[written] = '\0';
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

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot persist interval");
        return;
    }
    s_prefs.putUInt("interval", interval_ms);
    s_prefs.end();
}

void configSetPaired(bool paired) {
    cfgLock();
    s_cfg.paired = paired;
    cfgUnlock();

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot persist paired flag");
        return;
    }
    s_prefs.putBool("paired", paired);
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

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot persist filter runtime");
        return;
    }
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

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot persist network credentials");
        return;
    }
    s_prefs.putString("wifi_ssid", s_net.wifi_ssid);
    s_prefs.putString("wifi_pass", s_net.wifi_pass);
    s_prefs.putString("mqtt_pass", s_net.mqtt_pass);
    s_prefs.end();
}

void netClear() {
    cfgLock();
    memset(&s_net, 0, sizeof(s_net));
    cfgUnlock();

    if (!s_prefs.begin(kNamespace, false)) {
        AQSS_LOG("nvs: cannot clear network credentials");
        return;
    }
    s_prefs.remove("wifi_ssid");
    s_prefs.remove("wifi_pass");
    s_prefs.remove("mqtt_pass");
    s_prefs.end();
}
