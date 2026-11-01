#pragma once

#include <Arduino.h>

// Values that survive a reboot: NVS-backed, the ESP32-S3 has no EEPROM.
typedef struct {
    char space_id[24];
    float dust_baseline_us;
    uint32_t telemetry_interval_ms;
    uint32_t filter_runtime_s;
} RuntimeConfig;

// Wi-Fi + broker credentials, provisioned on the node (SoftAP portal) and stored in NVS.
typedef struct {
    char wifi_ssid[33];
    char wifi_pass[65];
    char mqtt_host[64];
    uint16_t mqtt_port;
    char mqtt_user[33];
    char mqtt_pass[65];
} NetConfig;

void configBegin();
RuntimeConfig configSnapshot();
NetConfig netConfig();

bool netConfigured();

void configSetSpaceId(const char* space_id);
void configSetDustBaseline(float baseline_us);
void configSetTelemetryInterval(uint32_t interval_ms);
void configSetFilterRuntime(uint32_t seconds);
void configPersistFilterRuntime();

void netSet(const char* ssid, const char* wifiPass, const char* host, uint16_t port,
            const char* user, const char* mqttPass);
void netClear();
