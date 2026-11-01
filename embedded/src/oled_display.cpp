#include "oled_display.h"

#include <U8g2lib.h>

#include "config.h"
#include "sensor_data.h"
#include "system_utils.h"

// Full framebuffer (1 KB) on the shared 400 kHz I2C bus with the SCD40.
static U8G2_SSD1306_128X64_NONAME_F_HW_I2C s_u8g2(U8G2_R0, U8X8_PIN_NONE, PIN_I2C_SCL,
                                                   PIN_I2C_SDA);

void oledBegin() {
    s_u8g2.setBusClock(I2C_CLOCK_HZ);
    if (!s_u8g2.begin()) {
        AQSS_LOG("oled: init failed (0x%02X not responding)", I2C_ADDR_OLED);
    }
    s_u8g2.setContrast(0xCF);
}

/** Line 1: identity on the left, link state as two dots on the right. */
static void drawStatusBar(const SensorData_t& d) {
    s_u8g2.setFont(u8g2_font_6x10_tr);
    s_u8g2.drawStr(2, 9, "AQSS");
    s_u8g2.drawStr(30, 9, d.space_id);

    s_u8g2.drawStr(66, 9, "WiFi");
    if (d.wifi_connected) {
        s_u8g2.drawDisc(94, 5, 2);
    } else {
        s_u8g2.drawCircle(94, 5, 2);
    }
    s_u8g2.drawStr(100, 9, "MQ");
    if (d.mqtt_connected) {
        s_u8g2.drawDisc(122, 5, 2);
    } else {
        s_u8g2.drawCircle(122, 5, 2);
    }
    s_u8g2.drawHLine(0, 11, 128);
}

/** Shown while the SoftAP setup portal is open — the operator reads this and joins from a phone. */
static void drawSetup(const SensorData_t& d) {
    s_u8g2.setFont(u8g2_font_helvB08_tr);
    s_u8g2.drawStr(32, 11, "AQSS SETUP");
    s_u8g2.drawHLine(0, 15, 128);

    s_u8g2.setFont(u8g2_font_6x10_tr);
    char line[48];
    snprintf(line, sizeof(line), "AP: %s", d.ap_ssid);
    s_u8g2.drawStr(2, 27, line);
    snprintf(line, sizeof(line), "pass: %s", AP_PASSWORD);
    s_u8g2.drawStr(2, 39, line);
    snprintf(line, sizeof(line), "http://%s", d.ap_ip);
    s_u8g2.drawStr(2, 51, line);
    if (d.wifi_target[0] != '\0') {
        snprintf(line, sizeof(line), "trying: %s", d.wifi_target);
        s_u8g2.drawStr(2, 62, line);
    } else {
        s_u8g2.drawStr(2, 62, "join AP, open browser");
    }
}

static void drawMain(const SensorData_t& d) {
    drawStatusBar(d);

    s_u8g2.setFont(u8g2_font_helvR08_tr);

    char line[32];
    if (d.dust_ready) {
        snprintf(line, sizeof(line), "PM2.5 %.1f ug/m3", d.pm25_ugm3);
    } else {
        snprintf(line, sizeof(line), "PM2.5 -- ug/m3");
    }
    s_u8g2.drawStr(2, 23, line);

    if (d.scd40_ready) {
        snprintf(line, sizeof(line), "CO2 %uppm T%.1f H%.0f%%", (unsigned)d.co2_ppm, d.temperature_c,
                 d.humidity_pct);
    } else {
        snprintf(line, sizeof(line), "CO2 -- T--.- H--");
    }
    s_u8g2.drawStr(2, 35, line);

    snprintf(line, sizeof(line), "FAN %s  L%u  %u%%", d.fan_state ? "ON" : "OFF",
             (unsigned)d.fan_level, (unsigned)d.fan_duty_pct);
    s_u8g2.drawStr(2, 47, line);

    s_u8g2.setFont(u8g2_font_6x10_tr);
    if (d.warning_active) {
        char msg[20];
        strlcpy(msg, d.warning_msg, sizeof(msg));
        s_u8g2.drawStr(2, 61, "!");
        s_u8g2.drawStr(12, 61, msg);
    } else if (d.dust_fault) {
        s_u8g2.drawStr(2, 61, "! dust sensor fault");
    } else if (d.dust_saturated) {
        s_u8g2.drawStr(2, 61, "! PM2.5 saturated");
    } else {
        snprintf(line, sizeof(line), "%s  %s  %luh", d.fan_mode == FAN_MODE_AUTO ? "AUTO" : "MANUAL",
                 d.mqtt_connected ? "online" : "offline", (unsigned long)(millis() / 3600000UL));
        s_u8g2.drawStr(2, 61, line);
    }
}

void Task_UpdateOLED(void* pvParam) {
    aqssWdtWatchCurrentTask();

    for (;;) {
        aqssWdtFeed();

        SensorData_t d = dataSnapshot();

        if (d.warning_active && (uint32_t)(millis() - d.warning_set_ms) > WARNING_TTL_MS) {
            dataLock();
            g_sensorData.warning_active = false;
            dataUnlock();
            d.warning_active = false;
        }

        s_u8g2.clearBuffer();
        if (d.provisioning) {
            drawSetup(d);
        } else {
            drawMain(d);
        }
        s_u8g2.sendBuffer();

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
