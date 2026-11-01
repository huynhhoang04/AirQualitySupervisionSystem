#pragma once

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "config.h"

#define AQSS_LOG(fmt, ...) Serial.printf("[" AQSS_DEVICE_ID "] " fmt "\n", ##__VA_ARGS__)

inline void aqssWdtInit() {
    esp_task_wdt_config_t cfg;
    cfg.timeout_ms = WDT_TIMEOUT_MS;
    cfg.idle_core_mask = 0;
    cfg.trigger_panic = true;

    esp_err_t err = esp_task_wdt_reconfigure(&cfg);
    if (err != ESP_OK) {
        err = esp_task_wdt_init(&cfg);
    }
    if (err != ESP_OK) {
        AQSS_LOG("WDT init failed (0x%x)", err);
    }
}

inline void aqssWdtWatchCurrentTask() {
    esp_task_wdt_add(nullptr);
}

inline void aqssWdtFeed() {
    esp_task_wdt_reset();
}
