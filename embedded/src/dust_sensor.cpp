#include "dust_sensor.h"

#include <driver/gpio.h>
#include <esp_timer.h>
#include <soc/gpio_reg.h>

#include "config.h"
#include "sensor_data.h"
#include "system_utils.h"

// GP2Y1023AU0F — integrated MCU, self-triggered IR LED, digital PWM output.
// Vo idles HIGH and is pulsed LOW for W µs every 10 ms (datasheet p.9).
static volatile uint32_t s_lastFallingUs;
static volatile uint32_t s_lowStartUs;
static volatile bool s_lowValid;
static volatile uint32_t s_widthUs;
static volatile uint32_t s_widthSeq;
static volatile uint32_t s_glitchCount;
static volatile uint32_t s_edgeCount;

static inline bool dustPinIsLow() {
    return (REG_READ(GPIO_IN_REG) & (1UL << PIN_DUST_PWM)) == 0;
}

static void IRAM_ATTR dustIsr() {
    const uint32_t now = (uint32_t)esp_timer_get_time();
    s_edgeCount = s_edgeCount + 1;

    if (dustPinIsLow()) {
        // Lock the falling edge once per 10 ms frame to reject divider/breadboard ringing.
        if ((uint32_t)(now - s_lastFallingUs) >= DUST_FRAME_MIN_US) {
            s_lastFallingUs = now;
            s_lowStartUs = now;
            s_lowValid = true;
        } else {
            s_lowValid = false;
        }
    } else if (s_lowValid) {
        const uint32_t width = now - s_lowStartUs;
        s_lowValid = false;

        if (width >= DUST_PULSE_MIN_US && width <= DUST_PULSE_ACCEPT_MAX_US) {
            s_widthUs = width;
            s_widthSeq = s_widthSeq + 1;
        } else {
            s_glitchCount = s_glitchCount + 1;
        }
    }
}

void Task_ReadDustPWM(void* pvParam) {
    aqssWdtWatchCurrentTask();

    pinMode(PIN_DUST_PWM, INPUT);
    vTaskDelay(pdMS_TO_TICKS(DUST_POWER_ON_GUARD_MS));

    attachInterrupt(digitalPinToInterrupt(PIN_DUST_PWM), dustIsr, CHANGE);

    uint32_t seenSeq = s_widthSeq;
    uint32_t windowStart = millis();
    uint32_t lastValidPulseMs = millis();
    uint32_t sumUs = 0;
    uint32_t samples = 0;
    bool faultLogged = false;

    for (;;) {
        aqssWdtFeed();

        const uint32_t seq = s_widthSeq;
        if (seq != seenSeq) {
            seenSeq = seq;
            sumUs += s_widthUs;
            samples++;
            lastValidPulseMs = millis();
        }

        if ((uint32_t)(millis() - windowStart) >= DUST_SAMPLE_WINDOW_MS) {
            const bool haveSamples = samples > 0;
            const float avgUs = haveSamples ? (float)sumUs / (float)samples : 0.0f;
            const bool fault = (uint32_t)(millis() - lastValidPulseMs) > DUST_FAULT_TIMEOUT_MS;

            dataLock();
            if (haveSamples) {
                float pm25 = (avgUs - g_sensorData.dust_baseline_us) / DUST_SENSITIVITY_US_PER_UGM3;
                if (pm25 < 0.0f) {
                    pm25 = 0.0f;
                }
                if (pm25 > PM25_MAX_UGM3) {
                    pm25 = PM25_MAX_UGM3;
                }
                g_sensorData.pm25_ugm3 = pm25;
                g_sensorData.dust_pulse_us = avgUs;
                g_sensorData.dust_ready = true;
                g_sensorData.dust_saturated = avgUs >= DUST_SATURATION_US;
            }
            g_sensorData.dust_fault = fault;
            const float pulseForLog = g_sensorData.dust_pulse_us;
            const float pmForLog = g_sensorData.pm25_ugm3;
            dataUnlock();

            if (fault != faultLogged) {
                faultLogged = fault;
                if (fault) {
                    AQSS_LOG("dust: no valid pulse for %lums (Vo stuck HIGH -> sensor fault)",
                             (unsigned long)DUST_FAULT_TIMEOUT_MS);
                } else {
                    AQSS_LOG("dust: pulses recovered");
                }
            }

            if (haveSamples) {
                AQSS_LOG("dust: W=%.0fus PM2.5=%.1f ug/m3 samples=%lu%s", pulseForLog, pmForLog,
                         (unsigned long)samples,
                         (avgUs >= DUST_SATURATION_US) ? " SATURATED" : "");
            } else {
                AQSS_LOG("dust: no valid pulse (edges=%lu glitches=%lu pin=%d)", (unsigned long)s_edgeCount,
                         (unsigned long)s_glitchCount, dustPinIsLow() ? 0 : 1);
            }

            sumUs = 0;
            samples = 0;
            windowStart = millis();
        }

        vTaskDelay(pdMS_TO_TICKS(DUST_POLL_INTERVAL_MS));
    }
}
