#include <Arduino.h>
#include <Wire.h>

#include "config.h"
#include "config_store.h"
#include "dust_sensor.h"
#include "fan_control.h"
#include "mqtt_network.h"
#include "oled_display.h"
#include "scd40_sensor.h"
#include "sensor_data.h"
#include "system_utils.h"

static void i2cScan(char* out, size_t len) {
    size_t pos = 0;
    if (len > 0) {
        out[0] = '\0';
    }
    for (uint8_t addr = 1; addr < 0x7F; addr++) {
        // A zero-byte probe to 0x62 while the SCD40 runs its periodic cycle resets
        // the 5 s counter (embedded-hardware-setup-guide.md §2 item 7), so skip it.
        if (addr == I2C_ADDR_SCD40) {
            continue;
        }
        Wire.beginTransmission(addr);
        if (Wire.endTransmission() == 0 && pos < len) {
            pos += snprintf(out + pos, len - pos, "0x%02X ", addr);
        }
    }
}

void setup() {
    Serial.begin(115200);
    delay(500);
    AQSS_LOG("=== AQSS Layer-1 firmware %s ===", AQSS_FW_VERSION);

    // The Intel fan pulls its PWM input high: drive it low before anything else.
    fanBegin();

    configBegin();
    const RuntimeConfig cfg = configSnapshot();
    dataInit(cfg.space_id, cfg.dust_baseline_us, cfg.filter_runtime_s);

    // Button held on power-up (3 s) re-opens the Wi-Fi setup portal.
    pinMode(PIN_BTN, INPUT_PULLUP);
    if (digitalRead(PIN_BTN) == LOW) {
        const uint32_t held = millis();
        while (digitalRead(PIN_BTN) == LOW && (uint32_t)(millis() - held) < 3000) {
            vTaskDelay(pdMS_TO_TICKS(50));
        }
        g_forceProvisioning = digitalRead(PIN_BTN) == LOW;
        AQSS_LOG("button %s", g_forceProvisioning ? "held -> setup portal" : "released -> normal boot");
    }

    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, I2C_CLOCK_HZ);
    Wire.setTimeOut(50);

    oledBegin();

    char devices[64];
    i2cScan(devices, sizeof(devices));
    AQSS_LOG("i2c: found [%s] (SCD40 0x%02X polled by its own task)", devices, I2C_ADDR_SCD40);
    AQSS_LOG("config: space=%s w0=%.0fus interval=%lums filter=%.1fh", cfg.space_id,
             cfg.dust_baseline_us, (unsigned long)cfg.telemetry_interval_ms,
             cfg.filter_runtime_s / 3600.0f);
    AQSS_LOG("heap: free=%lu min=%lu psram=%s", (unsigned long)ESP.getFreeHeap(),
             (unsigned long)ESP.getMinFreeHeap(), psramFound() ? "yes" : "no");

    aqssWdtInit();

    xTaskCreatePinnedToCore(Task_ReadSCD40, "scd40", 4096, nullptr, 2, nullptr, 1);
    xTaskCreatePinnedToCore(Task_ReadDustPWM, "dust", 3072, nullptr, 3, nullptr, 1);
    xTaskCreatePinnedToCore(Task_FanControl, "fan", 3072, nullptr, 3, nullptr, 1);
    xTaskCreatePinnedToCore(Task_UpdateOLED, "oled", 4096, nullptr, 1, nullptr, 1);
    xTaskCreatePinnedToCore(Task_MQTT_Network, "mqtt", 8192, nullptr, 2, nullptr, 0);

    AQSS_LOG("boot complete - serial commands: setup | forget | reboot");
}

void loop() {
    vTaskDelay(pdMS_TO_TICKS(10000));
}
