#include "scd40_sensor.h"

#include <SensirionI2cScd4x.h>
#include <Wire.h>

#include "config.h"
#include "sensor_data.h"
#include "system_utils.h"

static SensirionI2cScd4x s_scd40;

static void logScdError(const char* what, int16_t err) {
    char msg[64];
    errorToString(err, msg, sizeof(msg));
    AQSS_LOG("scd40: %s failed: %s", what, msg);
}

static void restartMeasurement() {
    const int16_t stopErr = s_scd40.stopPeriodicMeasurement();
    if (stopErr != 0) {
        logScdError("stopPeriodicMeasurement", stopErr);
    }
    vTaskDelay(pdMS_TO_TICKS(500));
    const int16_t startErr = s_scd40.startPeriodicMeasurement();
    if (startErr != 0) {
        logScdError("startPeriodicMeasurement", startErr);
    } else {
        AQSS_LOG("scd40: periodic measurement restarted");
    }
}

void Task_ReadSCD40(void* pvParam) {
    aqssWdtWatchCurrentTask();

    vTaskDelay(pdMS_TO_TICKS(SCD40_BOOT_DELAY_MS));
    s_scd40.begin(Wire, SCD41_I2C_ADDR_62);
    vTaskDelay(pdMS_TO_TICKS(30));

    // SCD40 has no single-shot mode: it must run the 5 s periodic cycle.
    restartMeasurement();

    float co2Ring[SCD40_RING_SIZE] = {0.0f};
    float tempRing[SCD40_RING_SIZE] = {0.0f};
    float humRing[SCD40_RING_SIZE] = {0.0f};
    uint8_t ringIndex = 0;
    uint8_t ringCount = 0;
    uint8_t errorStreak = 0;
    bool announced = false;

    for (;;) {
        aqssWdtFeed();
        vTaskDelay(pdMS_TO_TICKS(SCD40_POLL_INTERVAL_MS));

        bool dataReady = false;
        int16_t err = s_scd40.getDataReadyStatus(dataReady);
        if (err != 0) {
            if (++errorStreak >= 5) {
                errorStreak = 0;
                logScdError("getDataReadyStatus", err);
                restartMeasurement();
            }
            continue;
        }

        if (!dataReady) {
            errorStreak = 0;
            continue;
        }

        uint16_t co2 = 0;
        float temperature = 0.0f;
        float humidity = 0.0f;
        err = s_scd40.readMeasurement(co2, temperature, humidity);
        if (err != 0) {
            if (++errorStreak >= 5) {
                errorStreak = 0;
                logScdError("readMeasurement", err);
                restartMeasurement();
            }
            continue;
        }
        errorStreak = 0;

        // Reject readings outside the io_contract §12 acceptance window.
        if (co2 < CO2_MIN_PPM || co2 > CO2_MAX_PPM) {
            AQSS_LOG("scd40: rejected CO2=%uppm", (unsigned)co2);
            continue;
        }
        if (temperature < TEMP_MIN_C || temperature > TEMP_MAX_C) {
            continue;
        }
        if (humidity < HUM_MIN_PCT || humidity > HUM_MAX_PCT) {
            continue;
        }

        co2Ring[ringIndex] = (float)co2;
        tempRing[ringIndex] = temperature;
        humRing[ringIndex] = humidity;
        ringIndex = (uint8_t)((ringIndex + 1) % SCD40_RING_SIZE);
        if (ringCount < SCD40_RING_SIZE) {
            ringCount++;
        }

        float co2Avg = 0.0f;
        float tempAvg = 0.0f;
        float humAvg = 0.0f;
        for (uint8_t i = 0; i < ringCount; i++) {
            co2Avg += co2Ring[i];
            tempAvg += tempRing[i];
            humAvg += humRing[i];
        }
        co2Avg /= ringCount;
        tempAvg /= ringCount;
        humAvg /= ringCount;

        dataLock();
        g_sensorData.co2_ppm = co2Avg;
        g_sensorData.temperature_c = tempAvg;
        g_sensorData.humidity_pct = humAvg;
        g_sensorData.scd40_ready = true;
        dataUnlock();

        if (!announced) {
            announced = true;
            AQSS_LOG("scd40: first reading CO2=%.0fppm T=%.1fC RH=%.1f%%", co2Avg, tempAvg, humAvg);
        }
    }
}
