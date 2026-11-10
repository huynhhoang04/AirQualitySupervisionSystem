#pragma once

#include <Arduino.h>

// Values that survive a reboot: NVS-backed, the ESP32-S3 has no EEPROM.
typedef struct {
    char space_id[24];
    float dust_baseline_us;
    uint32_t telemetry_interval_ms;
    uint32_t filter_runtime_s;
    bool paired;              // backend confirmed the room binding (set_space received)
} RuntimeConfig;

// What the operator enters on the node: Wi-Fi credentials + the broker password.
// The broker host/port/user are compile-time constants in config.h.
typedef struct {
    char wifi_ssid[33];
    char wifi_pass[65];
    char mqtt_pass[65];
} NetConfig;

void configBegin();
RuntimeConfig configSnapshot();
NetConfig netConfig();

bool netConfigured();

/**
 * The six-character code shown on the OLED. Derived from the chip MAC, so it is stable across
 * reboots and re-flashes without occupying NVS. Symbols come from a 32-character alphabet that
 * omits I, O, 0 and 1 — the pairs people mistype from a photograph of the screen.
 */
void pairingCode(char* out, size_t len);

void configSetSpaceId(const char* space_id);
void configClearSpaceId();
void configSetDustBaseline(float baseline_us);
void configSetTelemetryInterval(uint32_t interval_ms);
void configSetFilterRuntime(uint32_t seconds);
void configPersistFilterRuntime();
void configSetPaired(bool paired);

void netSet(const char* ssid, const char* wifiPass, const char* mqttPass);
void netClear();
