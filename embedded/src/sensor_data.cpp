#include "sensor_data.h"

#include <string.h>

#include "config.h"
#include "config_store.h"

SensorData_t g_sensorData;
volatile bool g_forceProvisioning = false;
FanCommand_t g_fanCmd;
SemaphoreHandle_t g_dataMutex = nullptr;

void dataInit(const char* space_id, float dust_baseline_us, uint32_t filter_runtime_s) {
    if (g_dataMutex == nullptr) {
        g_dataMutex = xSemaphoreCreateMutex();
    }

    memset(&g_sensorData, 0, sizeof(g_sensorData));
    strlcpy(g_sensorData.space_id, space_id, sizeof(g_sensorData.space_id));
    g_sensorData.dust_baseline_us = dust_baseline_us;
    g_sensorData.filter_runtime_s = filter_runtime_s;
    g_sensorData.provisioning = false;

    pairingCode(g_sensorData.pairing_code, sizeof(g_sensorData.pairing_code));
    g_sensorData.paired = configSnapshot().paired;

    memset(&g_fanCmd, 0, sizeof(g_fanCmd));
    g_fanCmd.on = false;
    g_fanCmd.level = 0;
    g_fanCmd.mode = FAN_MODE_AUTO;
    g_fanCmd.last_command_ms = 0;
}

void dataLock() {
    xSemaphoreTake(g_dataMutex, portMAX_DELAY);
}

void dataUnlock() {
    xSemaphoreGive(g_dataMutex);
}

SensorData_t dataSnapshot() {
    SensorData_t copy;
    dataLock();
    copy = g_sensorData;
    dataUnlock();
    return copy;
}

FanCommand_t fanCmdSnapshot() {
    FanCommand_t copy;
    dataLock();
    copy = g_fanCmd;
    dataUnlock();
    return copy;
}

void fanCmdSet(bool on, uint8_t level, FanMode_t mode, bool fromBackend) {
    if (level > 3) {
        level = 3;
    }
    dataLock();
    g_fanCmd.on = on && level > 0;
    g_fanCmd.level = level;
    g_fanCmd.mode = mode;
    if (fromBackend) {
        g_fanCmd.last_command_ms = millis();
    }
    dataUnlock();
}

void fanCmdFailSafeOff() {
    dataLock();
    g_fanCmd.on = false;
    g_fanCmd.level = 0;
    dataUnlock();
}
