#pragma once

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#include "config.h"

typedef enum { FAN_MODE_AUTO = 0, FAN_MODE_MANUAL = 1 } FanMode_t;

// Single source of truth shared by every task. Never touch the fields directly:
// call dataLock()/dataUnlock(), or take a dataSnapshot() copy.
typedef struct {
    char space_id[24];

    bool warning_active;
    char warning_msg[96];
    char warning_severity[16];
    uint32_t warning_set_ms;

    float co2_ppm;
    float temperature_c;
    float humidity_pct;
    bool scd40_ready;

    float pm25_ugm3;
    float dust_pulse_us;
    float dust_baseline_us;
    bool dust_ready;
    bool dust_saturated;
    bool dust_fault;

    uint8_t fan_level;
    uint8_t fan_duty_pct;
    bool fan_state;
    uint8_t fan_mode;
    uint32_t filter_runtime_s;

    int wifi_rssi;
    bool wifi_connected;
    bool mqtt_connected;
    bool provisioning;         // SoftAP setup portal is active
    bool paired;               // backend confirmed the room binding
    char pairing_code[PAIRING_CODE_LEN + 1];
    char ap_ssid[24];
    char ap_ip[16];
    char wifi_target[33];
    uint32_t timestamp;
    uint32_t min_free_heap;
} SensorData_t;

// Commanded actuation, owned by the backend / physical button.
typedef struct {
    bool on;
    uint8_t level;  // 0..3
    FanMode_t mode;
    uint32_t last_command_ms;
} FanCommand_t;

extern SensorData_t g_sensorData;// Set at boot when the button is held: open the setup portal even if credentials exist.
extern volatile bool g_forceProvisioning;
extern FanCommand_t g_fanCmd;
extern SemaphoreHandle_t g_dataMutex;

void dataInit(const char* space_id, float dust_baseline_us, uint32_t filter_runtime_s);

void dataLock();
void dataUnlock();
SensorData_t dataSnapshot();
FanCommand_t fanCmdSnapshot();

void fanCmdSet(bool on, uint8_t level, FanMode_t mode, bool fromBackend);
void fanCmdFailSafeOff();
