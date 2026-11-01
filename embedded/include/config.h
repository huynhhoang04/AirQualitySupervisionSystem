#pragma once

// Deployment secrets live in include/secrets.h (gitignored); see secrets.example.h.
// Every credential below can also be overridden with a -D build flag.
#if __has_include("secrets.h")
#include "secrets.h"
#endif

// ─── Identity ─────────────────────────────────────────────────────────────────
#ifndef AQSS_DEVICE_ID
#define AQSS_DEVICE_ID "AQSS_ESP32S3_01"
#endif
#ifndef AQSS_DEFAULT_SPACE_ID
#define AQSS_DEFAULT_SPACE_ID "SP_201"
#endif
#define AQSS_FW_VERSION "2.0.0"

// ─── Pin map (single integrated node) ────────────────────────────────────────
#define PIN_I2C_SDA 8
#define PIN_I2C_SCL 9
#define PIN_DUST_PWM 18
#define PIN_FAN_PWM 5
#define PIN_BTN 6
#define PIN_STATUS_LED 7

// ─── I2C bus (SCD40 + SSD1306 share it; lock at 400 kHz) ─────────────────────
#define I2C_CLOCK_HZ 400000
#define I2C_ADDR_OLED 0x3C
#define I2C_ADDR_SCD40 0x62

// ─── GP2Y1023AU0F ────────────────────────────────────────────────────────────
// Datasheet p.9: Vo idles HIGH and is pulsed LOW for W on a 10 ms period.
// The built-in MCU outputs PWM of (A/D amplified − A/D initial + A/D of 1 V), and
// 1 V of a 5 V / 10-bit scale maps to 7000 µs * 204.6 / 1023 ≈ 1400 µs. Therefore
// 0 µg/m³ always corresponds to W = 1400 µs and 100 µg/m³ to +1400 µs (K = 14 µs/µg).
#define DUST_FRAME_MIN_US 8000     // new frame must be >= 8 ms after the previous one
#define DUST_PULSE_MIN_US 7        // datasheet min 6.8 µs
#define DUST_PULSE_ACCEPT_MAX_US 8000  // 7 ms is the in-spec max; tolerate smoke overduty
#define DUST_SATURATION_US 6900    // amplifier about to saturate (~393 µg/m³)
#define DUST_SENSITIVITY_US_PER_UGM3 14.0f
#define DUST_ZERO_PULSE_US 1400.0f
#define DUST_SAMPLE_WINDOW_MS 1000
#define DUST_POLL_INTERVAL_MS 5
#define DUST_FAULT_TIMEOUT_MS 3000  // no valid pulse -> sensor stuck HIGH (datasheet failure mode)
#define DUST_POWER_ON_GUARD_MS 1200  // Vo stays HIGH for 1 s after power-up

// ─── Sensor validity (io_contract.md §12) ─────────────────────────────────────
#define PM25_MAX_UGM3 500.0f
#define CO2_MIN_PPM 300
#define CO2_MAX_PPM 10000
#define TEMP_MIN_C (-40.0f)
#define TEMP_MAX_C 85.0f
#define HUM_MIN_PCT 0.0f
#define HUM_MAX_PCT 100.0f

// ─── SCD40 ────────────────────────────────────────────────────────────────────
#define SCD40_RING_SIZE 5
#define SCD40_POLL_INTERVAL_MS 1000
#define SCD40_BOOT_DELAY_MS 1000

// ─── Intel 4-wire fan ─────────────────────────────────────────────────────────
#define FAN_PWM_FREQ 25000
#define FAN_PWM_RES 8
#define FAN_MIN_DUTY 51          // ~20 %: below this a BLDC fan may stall
#define FAN_KICKSTART_MS 250
#define FAN_TICK_MS 50
#define FAN_FAILSAFE_MS 600000UL  // 10 min without a command in AUTO -> OFF
#define FAN_STATE_PUBLISH_MS 5000
#define FAN_BUTTON_DEBOUNCE_MS 30
#define FAN_BUTTON_HOLD_MS 3000

// ─── Wi-Fi / NTP ──────────────────────────────────────────────────────────────
// Empty by design: the node provisions itself (SoftAP portal, value stored in NVS).
// include/secrets.h (gitignored) may pre-seed them for a lab; see secrets.example.h.
#ifndef WIFI_SSID
#define WIFI_SSID ""
#endif
#ifndef WIFI_PASSWORD
#define WIFI_PASSWORD ""
#endif
#define AP_SSID_PREFIX "AQSS-"
#define AP_PASSWORD "aqss1234"
#define STA_CONNECT_TIMEOUT_MS 15000UL  // how long one join attempt gets before the AP comes up
#define STA_RETRY_MS 15000UL            // retry period while the setup AP is open
#define WIFI_LOST_SETUP_MS 60000UL      // Wi-Fi down this long at runtime -> back to setup
#define NTP_SERVER_1 "pool.ntp.org"
#define NTP_SERVER_2 "time.google.com"
#define NTP_GMT_OFFSET_S (7 * 3600)
#define NTP_RESYNC_MS 3600000UL
#define TIME_VALID_EPOCH 1700000000UL

// ─── MQTT ─────────────────────────────────────────────────────────────────────
#ifndef MQTT_SERVER
#define MQTT_SERVER ""
#endif
// 1 = MQTT over TLS (internet brokers: HiveMQ Cloud, EMQX Cloud, mosquitto + Let's Encrypt).
#ifndef MQTT_USE_TLS
#define MQTT_USE_TLS 0
#endif
#ifndef MQTT_PORT
#if MQTT_USE_TLS
#define MQTT_PORT 8883
#else
#define MQTT_PORT 1883
#endif
#endif
#ifndef MQTT_USER
#define MQTT_USER ""
#endif
#ifndef MQTT_PASSWORD
#define MQTT_PASSWORD ""
#endif
#define MQTT_BUFFER_SIZE 512
#define MQTT_RECONNECT_MS 5000
#define MQTT_KEEPALIVE_S 30
#define MQTT_SOCKET_TIMEOUT_S 5
#define TELEMETRY_INTERVAL_DEFAULT_MS 5000
#define TELEMETRY_INTERVAL_MIN_MS 1000
#define TELEMETRY_INTERVAL_MAX_MS 60000
#define OFFLINE_BUFFER_SLOTS 60

// ─── OLED ─────────────────────────────────────────────────────────────────────
#define OLED_SHIFT_PERIOD_MS 1800000UL
#define WARNING_TTL_MS (30UL * 60UL * 1000UL)

// ─── System ───────────────────────────────────────────────────────────────────
#define WDT_TIMEOUT_MS 30000
#define FILTER_RUNTIME_PERSIST_MS 300000UL
