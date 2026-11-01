#include "fan_control.h"

#include <Arduino.h>

#include "config.h"
#include "config_store.h"
#include "sensor_data.h"
#include "system_utils.h"

// Intel 4-wire fan: pin 2 sits on a constant +12 V, pin 4 is the 25 kHz PWM input.
// Duty per commanded level (io_contract §2): 0 → 0 %, 1 → 35 %, 2 → 60 %, 3 → 90 %.
static const uint8_t kLevelDuty[4] = {0, 89, 153, 230};

static bool s_appliedOn = false;
static uint8_t s_appliedDuty = 0;

// The Intel fan pulls its PWM input high, so it spins at 100 % until we drive the pin.
void fanBegin() {
    ledcAttach(PIN_FAN_PWM, FAN_PWM_FREQ, FAN_PWM_RES);
    ledcWrite(PIN_FAN_PWM, 0);
}

static void applyDuty(bool on, uint8_t level) {
    uint8_t duty = (on && level > 0) ? kLevelDuty[level] : 0;
    if (duty > 0 && duty < FAN_MIN_DUTY) {
        duty = FAN_MIN_DUTY;
    }

    if (duty > 0 && !s_appliedOn) {
        ledcWrite(PIN_FAN_PWM, 255);  // kickstart a stalled BLDC
        vTaskDelay(pdMS_TO_TICKS(FAN_KICKSTART_MS));
    }
    ledcWrite(PIN_FAN_PWM, duty);

    s_appliedOn = duty > 0;
    s_appliedDuty = duty;
}

void Task_FanControl(void* pvParam) {
    aqssWdtWatchCurrentTask();

    pinMode(PIN_BTN, INPUT_PULLUP);
    pinMode(PIN_STATUS_LED, OUTPUT);

    bool rawPressed = false;
    bool stablePressed = false;
    uint32_t lastEdgeMs = millis();
    uint32_t pressStartMs = 0;
    bool holdHandled = false;

    uint32_t runtimeS = configSnapshot().filter_runtime_s;
    uint32_t runtimeAccumMs = 0;
    uint32_t lastPersistMs = millis();

    for (;;) {
        aqssWdtFeed();
        const uint32_t now = millis();

        // ── Manual override button (always wins over the backend) ────────────────
        const bool raw = digitalRead(PIN_BTN) == LOW;
        if (raw != rawPressed) {
            rawPressed = raw;
            lastEdgeMs = now;
        } else if (raw != stablePressed && (uint32_t)(now - lastEdgeMs) >= FAN_BUTTON_DEBOUNCE_MS) {
            stablePressed = raw;
            if (stablePressed) {
                pressStartMs = now;
                holdHandled = false;
            } else if (!holdHandled && (uint32_t)(now - pressStartMs) < FAN_BUTTON_HOLD_MS) {
                const FanCommand_t current = fanCmdSnapshot();
                const bool newOn = !current.on;
                const uint8_t level = current.level > 0 ? current.level : 2;
                fanCmdSet(newOn, level, FAN_MODE_MANUAL, false);
                AQSS_LOG("button: MANUAL %s L%u", newOn ? "ON" : "OFF", level);
            }
        }

        if (stablePressed && !holdHandled && (uint32_t)(now - pressStartMs) >= FAN_BUTTON_HOLD_MS) {
            holdHandled = true;
            const FanCommand_t current = fanCmdSnapshot();
            fanCmdSet(current.on, current.level, FAN_MODE_AUTO, false);
            AQSS_LOG("button: hold -> AUTO mode");
        }

        // ── Fail-safe: backend silent for 10 min in AUTO -> fan OFF ──────────────
        FanCommand_t cmd = fanCmdSnapshot();
        if (cmd.mode == FAN_MODE_AUTO && cmd.on &&
            (uint32_t)(now - cmd.last_command_ms) > FAN_FAILSAFE_MS) {
            fanCmdFailSafeOff();
            cmd = fanCmdSnapshot();
            AQSS_LOG("failsafe: no backend command for %lus -> fan OFF",
                     (unsigned long)(FAN_FAILSAFE_MS / 1000));
        }

        // ── Apply ───────────────────────────────────────────────────────────────
        const bool wantOn = cmd.on && cmd.level > 0;
        if (wantOn != s_appliedOn || (wantOn && kLevelDuty[cmd.level] != s_appliedDuty)) {
            applyDuty(wantOn, cmd.level);
            AQSS_LOG("fan: %s L%u duty=%u%%", s_appliedOn ? "ON" : "OFF", cmd.level,
                     (unsigned)((s_appliedDuty * 100u) / 255u));
        }

        if (s_appliedOn) {
            runtimeAccumMs += FAN_TICK_MS;
            if (runtimeAccumMs >= 1000) {
                runtimeAccumMs -= 1000;
                runtimeS++;
                configSetFilterRuntime(runtimeS);
            }
        } else {
            runtimeAccumMs = 0;
        }

        if ((uint32_t)(now - lastPersistMs) >= FILTER_RUNTIME_PERSIST_MS) {
            lastPersistMs = now;
            configPersistFilterRuntime();
        }

        dataLock();
        g_sensorData.fan_state = s_appliedOn;
        g_sensorData.fan_level = s_appliedOn ? cmd.level : 0;
        g_sensorData.fan_duty_pct = (uint8_t)((s_appliedDuty * 100u) / 255u);
        g_sensorData.fan_mode = cmd.mode;
        g_sensorData.filter_runtime_s = runtimeS;
        const bool warn = g_sensorData.warning_active || g_sensorData.dust_fault;
        dataUnlock();

        // Status LED: solid = fan running, blink = warning/fault, off = idle.
        digitalWrite(PIN_STATUS_LED, (s_appliedOn || (warn && ((now / 500) % 2 == 0))) ? HIGH : LOW);

        vTaskDelay(pdMS_TO_TICKS(FAN_TICK_MS));
    }
}
