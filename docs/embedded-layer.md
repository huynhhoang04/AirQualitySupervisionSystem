# Detailed Plan — Layer 1: Embedded System Layer

> **Version:** 3.0 — **Single Integrated Node** (sensing + display + fan actuation on one ESP32-S3)
> **Reference:** [hardware_datasheet_reference.md](./hardware_datasheet_reference.md) · [aqss-concept-v2.md](./aqss-concept-v2.md) · [io_contract.md](./io_contract.md)
>
> **Change from the earlier V2 draft:** the separate Actuator Node + relay is **removed**. One ESP32-S3 does everything, and the filter fan is an **Intel 4-wire PWM fan** driven through its PWM control pin (the 12 V rail stays constant) — not a relay.

---

## 📋 1. Hardware Components & Key Specifications

| # | Component | Model | Interface | Power | I2C Addr |
| :---: | :--- | :--- | :---: | :---: | :---: |
| 1 | Microcontroller | ESP32-S3-DevKitC-1 (N16R8) | — | 5V (VIN) | — |
| 2 | CO2/Temp/Hum Sensor | Sensirion SCD40 | **I2C** | 2.4–5.5V | **0x62** |
| 3 | PM2.5 Dust Sensor | Sharp GP2Y1023AU0F | **PWM Digital** | 5V | — |
| 4 | OLED Display | SSD1306 128×64 0.96" | **I2C** | 3.3V | **0x3C** |
| 5 | **Filter fan** | **Intel CPU cooler fan, 4-wire PWM** | **PWM control pin** | 12V | — |
| 6 | Filter media | HEPA H13 (mounted on fan outlet) | — | — | — |
| 7 | Buck converter | LM2596S module (12V → 5V) | — | — | — |
| 8 | Power supply | 12V/400W PSU (+ fuse 2–3A) | — | — | — |
| 9 | Manual override | Push button + status LED | GPIO | 3.3V | — |

**Physical limit (must be stated):** one Intel stock fan moves ~85–100 m³/h free-air and **~30–50 m³/h through HEPA media** ⇒ suitable for **rooms ≤ 10 m²** only. Larger rooms need a fan bank (5–6 fans for a 20 m² room).

---

## 🔌 2. Hardware Wiring

### 2.1 System Diagram

```text
  ┌────────────────────────── POWER BLOCK ──────────────────────────┐
  │                                                                  │
  │  12V/400W PSU ──[Fuse 2-3A]──┬── Fan pin 2 (+12V, CONSTANT)      │
  │                              │                                   │
  │                              └── LM2596 IN                       │
  │                                    LM2596 OUT (5.0V) ── ESP32 VIN│
  │                                                                  │
  │  GND (star): PSU ── Fan pin 1 ── ESP32 GND   ← MANDATORY common  │
  └──────────────────────────────────────────────────────────────────┘

  ┌────────────────────────── I2C BLOCK (400 kHz) ───────────────────┐
  │  ESP32 GPIO 8 (SDA) ──┬── SCD40 SDA (0x62)                       │
  │                       └── OLED SDA  (0x3C)                       │
  │  ESP32 GPIO 9 (SCL) ──┬── SCD40 SCL                              │
  │                       └── OLED SCL                               │
  └──────────────────────────────────────────────────────────────────┘

  ┌────────────────────────── DUST SENSOR BLOCK ─────────────────────┐
  │  GP2Y1023 Vo ──[R1=1kΩ]──●──[R2=2kΩ]── GND                       │
  │                          └──── GPIO 18 (input)                   │
  │  GP2Y1023 V_LED ──[150Ω]── +5V,  C=220µF to LED-GND              │
  └──────────────────────────────────────────────────────────────────┘

  ┌────────────────────────── FAN CONTROL BLOCK ─────────────────────┐
  │  ESP32 GPIO 5 ──[LEDC 25 kHz]──[2N2222 if needed]── Fan pin 4     │
  │                (PWM INPUT — the 12V rail is NOT switched)         │
  │  Fan pin 1 = GND · pin 2 = +12V · pin 3 = Tach (reserved)         │
  └──────────────────────────────────────────────────────────────────┘

  ┌────────────────────────── INTERFACE BLOCK ───────────────────────┐
  │  GPIO 6 ── Push button (manual override, INPUT_PULLUP)            │
  │  GPIO 7 ── Status LED + 220Ω ── GND                               │
  └──────────────────────────────────────────────────────────────────┘
```

### 2.2 Pin Connection Table

| Device | Device Pin | ESP32-S3 Pin | Protocol | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **SCD40 (Module)**| Pin VCC | 3V3 | Power | Plugs directly to 3.3V (breakout board handles VDD/VDDH internally) |
| | Pin 3 (GND) | GND | Power | |
| | Pin 4 (SDA) | **GPIO 8** | I2C Data | Pull-up on module |
| | Pin 5 (SCL) | **GPIO 9** | I2C Clock | Pull-up on module |
| **OLED SSD1306** | Pin 1 (GND) | GND | Power | ⚠️ Check silkscreen (GND-VCC vs VCC-GND)! |
| | Pin 2 (VCC) | 3V3 | Power | Onboard LDO |
| | Pin 3 (SCL) | **GPIO 9** | I2C Clock | Shared bus with SCD40 |
| | Pin 4 (SDA) | **GPIO 8** | I2C Data | Shared bus with SCD40 |
| **GP2Y1023AU0F** | Pin 1 (Vo) | **GPIO 18** (via divider) | PWM Input | R1=1kΩ + R2=2kΩ |
| | Pin 2 (LED-GND) | GND | Power | Star ground, isolated |
| | Pin 3 (S-GND) | GND | Power | Shield/casing |
| | Pin 4 (V_LED) | 5V via 150Ω | Power | + 220µF to LED-GND |
| | Pin 5 (VCC) | 5V | Power | Regulated 5V ±5% |
| **Intel fan (4-wire)** | Pin 1 (GND) | GND | Power | Common GND mandatory |
| | Pin 2 (+12V) | 12V rail via fuse | Power | **Constant — never PWM this line** |
| | Pin 3 (Tach) | *(reserved)* | Input | For future RPM feedback |
| | Pin 4 (PWM) | **GPIO 5** | PWM Input | LEDC 25 kHz; level-shift via 2N2222 if the fan ignores 3.3V |
| **Button** | — | **GPIO 6** | GPIO Input | `INPUT_PULLUP`, manual override |
| **Status LED** | — | **GPIO 7** | GPIO Output | + 220Ω series |

### 2.3 Pin Selection Rationale (GPIO 5 & 18)

- **Not strapping pins** (avoid GPIO 0, 3, 45, 46).
- **Not used by USB** (GPIO 19, 20) or UART bridge (GPIO 43, 44).
- **Not blocked by Octal PSRAM** (GPIO 33–37 are locked on N16R8).
- GPIO 18 is in the ADC2 group but used as a **digital input** → unaffected by Wi-Fi.

### 2.4 Pre-Power Checklist (do this before the first boot)

1. **Set the LM2596 output to exactly 5.0 V with a multimeter BEFORE connecting the ESP32.** Modules ship unadjusted and can exceed 5 V — this destroys the board.
2. OLED silkscreen orientation (GND-VCC vs VCC-GND) — reversed polarity kills the SSD1306.
3. If the PSU is an **ATX** unit: short **PS_ON (green wire)** to GND to switch it on without a motherboard; it also provides a free 5 V rail (buck optional).
4. Identify the fan's 4 wires with a multimeter — do not trust colours.
5. Verify common GND between PSU, fan and ESP32 (continuity test).

---

## 🛠️ 3. Embedded Software Architecture

### 3.1 Development Platform

| Parameter | Selection |
| :--- | :--- |
| **Build System** | PlatformIO |
| **Framework** | Arduino Core for ESP32-S3 |
| **Board** | `esp32-s3-devkitc-1` (PlatformIO) |
| **Language** | C++17 |
| **RTOS** | FreeRTOS (integrated in ESP32 Arduino Core) |
| **Monitor Speed** | 115200 baud |

### 3.2 Libraries

| Library | Version | Purpose |
| :--- | :--- | :--- |
| `SensirionI2CScd4x` | Latest | Official Sensirion driver for SCD40 (I2C) |
| `U8g2` | Latest | OLED SSD1306 128×64 driver (full framebuffer, UTF-8) |
| `WiFi` | Built-in | 2.4 GHz Wi-Fi connectivity |
| `PubSubClient` | Latest | Lightweight MQTT client |
| `ArduinoJson` | v7.x | Serialize/Deserialize JSON payloads |
| `Preferences` | Built-in | NVS storage (space_id, fan baseline W₀, config) |
| LEDC | Built-in | Hardware PWM for the fan |

> **AQI is no longer computed on the device.** AQI is a NowCast-derived, display-only metric computed by the backend (see `io_contract.md` §13). This removes `aqi_calculator` from the firmware entirely.

### 3.3 FreeRTOS Task Allocation (Dual-Core)

```text
┌─── Core 0 (PRO_CPU) ──────────────────────────────────────────┐
│  [System Tasks - Automatic] wifi_task, lwIP, events           │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Task_MQTT_Network (Priority 2, Stack 8192)              │  │
│  │  • Wi-Fi connection (auto-reconnect)                    │  │
│  │  • NTP sync every 1 hour                                │  │
│  │  • MQTT connect (with LWT) / publish / subscribe        │  │
│  │  • Publish telemetry JSON every 5 seconds               │  │
│  │  • Publish actuator/state on change + every 5 s when ON │  │
│  │  • Handle server commands (set_actuator, warnings...)   │  │
│  │  • Fail-safe watchdog: no command for 10 min → fan OFF  │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘

┌─── Core 1 (APP_CPU) ──────────────────────────────────────────┐
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Task_ReadSCD40 (Priority 2, Stack 4096)                 │  │
│  │  • readMeasurement() every 5 s, moving average N=5      │  │
│  └─────────────────────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Task_ReadDustPWM (Priority 3, Stack 4096)               │  │
│  │  • IRAM_ATTR ISR pulse measurement (~100 samples/s)     │  │
│  │  • Subtract baseline W₀ → µg/m³, filter outliers        │  │
│  └─────────────────────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Task_FanControl (Priority 3, Stack 3072)                │  │
│  │  • Apply commanded level → PWM duty (LEDC 25 kHz)       │  │
│  │  • Kickstart pulse on start-up from OFF                 │  │
│  │  • Accumulate filter runtime                            │  │
│  │  • Read manual override button (priority over network)  │  │
│  └─────────────────────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Task_UpdateOLED (Priority 1, Stack 4096)                │  │
│  │  • Values + fan state + backend early warnings          │  │
│  │  • Anti burn-in pixel shift, auto-sleep                 │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

### 3.4 Shared Data Structure & Thread Safety

```cpp
// Shared state across tasks (mutex-protected)
typedef struct {
    char  space_id[24];        // NVS-persisted, set via set_space
    bool  warning_active;      // Early warning from backend
    char  warning_msg[96];     // Warning message from display_warning

    // SCD40
    float co2_ppm;
    float temperature_c;
    float humidity_pct;
    bool  scd40_ready;

    // GP2Y1023AU0F
    float pm25_ugm3;
    float dust_pulse_us;       // Average pulse width (µs) — debug
    bool  dust_ready;

    // Fan (V2 actuation)
    uint8_t fan_level;         // 0-3 commanded level
    uint8_t fan_duty_pct;      // 0-100 actual duty
    bool    fan_state;         // ON/OFF
    uint8_t fan_mode;          // 0=AUTO, 1=MANUAL
    uint32_t filter_runtime_s; // Cumulative filter runtime (seconds)

    // System
    int   wifi_rssi;
    bool  mqtt_connected;
    uint32_t timestamp;
    uint32_t min_free_heap;
} SensorData_t;

SensorData_t g_sensorData;
SemaphoreHandle_t g_dataMutex;
```

> `aqi_index` / `aqi_level` were **removed** — the device no longer computes AQI (server-side NowCast, display-only). The old `char aqi_level[16]` also overflowed on `"Unhealthy for Sensitive Groups"` (30 chars).

### 3.5 MQTT Telemetry Payload

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "space_id": "SP_201",
  "timestamp": 1724217600,
  "sensors": {
    "pm25": 35.4,
    "co2": 620.0,
    "temperature": 29.5,
    "humidity": 68.2
  },
  "system": {
    "wifi_rssi": -65,
    "uptime_sec": 3600,
    "free_heap": 245760,
    "min_free_heap": 185000
  }
}
```

**MQTT Topics** (full registry in `io_contract.md` §8):

| Topic | Direction | Notes |
| :--- | :--- | :--- |
| `aqss/devices/{id}/telemetry` | Publish | QoS 1, every 5 s |
| `aqss/devices/{id}/actuator/state` | Publish | On change + every 5 s while ON |
| `aqss/devices/{id}/status` | Publish | `ONLINE` on connect (retain), `OFFLINE` via LWT |
| `aqss/devices/{id}/command` | Subscribe | `set_actuator`, `display_warning`, `set_space`, `reboot`, ... |

> Buffer size must be **≥512 bytes** (`mqttClient.setBufferSize(512)` and `char jsonBuffer[512]`). The V1 384-byte buffer overflows once `space_id` + `min_free_heap` are added.

---

## 🧪 4. GP2Y1023AU0F Reading (PWM Interrupt-Based)

> **⚠️ GP2Y1023AU0F ≠ GP2Y1010AU0F.** Integrated MCU, self-triggered IR LED, digital PWM output. Never `analogRead()`, never pulse an LED pin.

### 4.1 Conversion

```
Dust (µg/m³) = (W − W₀) / 14.0          W = pulse width (µs), W₀ = clean-air baseline
PWM period 10 ms (100 Hz) · pulse range 6.8 µs – 7000 µs · detection 0–500 µg/m³ (saturates ~750)
```

### 4.2 Reading Code

```cpp
#include <Preferences.h>

#define DUST_PWM_PIN          18
#define DUST_SENSITIVITY      14.0f   // µs per µg/m³
#define DUST_SAMPLE_WINDOW_MS 1000
#define DUST_PULSE_MIN_US     7       // datasheet min ≈ 6.8 µs  (V1 used 6 — too permissive)
#define DUST_PULSE_MAX_US     7000    // datasheet max = 7.0 ms  (V1 used 7500 — accepted invalid pulses)
#define PM25_SATURATION       750.0f  // physical saturation limit

Preferences nvs;                        // NVS — the ESP32-S3 has NO EEPROM
volatile uint32_t _dustPulseStart = 0;
volatile uint32_t _dustPulseWidth = 0;
volatile bool     _dustNewPulse   = false;

void IRAM_ATTR dustPWM_ISR() {
    uint32_t now = micros();
    if (digitalRead(DUST_PWM_PIN) == HIGH) {
        _dustPulseStart = now;
    } else if (_dustPulseStart > 0) {
        _dustPulseWidth = now - _dustPulseStart;
        _dustNewPulse = true;
    }
}

void Task_ReadDustPWM(void* pvParam) {
    pinMode(DUST_PWM_PIN, INPUT);
    vTaskDelay(pdMS_TO_TICKS(1500));      // GP2Y1023 holds HIGH for 1 s at boot

    // Baseline W₀ from NVS (set via `recalibrate_dust` in clean air)
    nvs.begin("aqss", false);
    float baselineW0 = nvs.getFloat("w0", 1350.0f);
    nvs.end();

    attachInterrupt(digitalPinToInterrupt(DUST_PWM_PIN), dustPWM_ISR, CHANGE);

    float sumPulse = 0; int sampleCount = 0;
    uint32_t lastCalc = millis();

    for (;;) {
        if (_dustNewPulse) {
            _dustNewPulse = false;
            uint32_t pw = _dustPulseWidth;
            if (pw >= DUST_PULSE_MIN_US && pw <= DUST_PULSE_MAX_US) {
                sumPulse += pw; sampleCount++;
            }
        }

        if (millis() - lastCalc >= DUST_SAMPLE_WINDOW_MS && sampleCount > 0) {
            float avgPulse = sumPulse / sampleCount;
            sumPulse = 0; sampleCount = 0; lastCalc = millis();

            float delta = avgPulse - baselineW0;
            if (delta < 0) delta = 0;
            float pm25 = delta / DUST_SENSITIVITY;
            if (pm25 > PM25_SATURATION) pm25 = PM25_SATURATION;   // flag saturation, not a real value

            if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
                g_sensorData.pm25_ugm3 = pm25;
                g_sensorData.dust_pulse_us = avgPulse;
                g_sensorData.dust_ready = true;
                xSemaphoreGive(g_dataMutex);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }
}
```

---

## 📡 5. SCD40 Reading (I2C)

```cpp
#include <SensirionI2CScd4x.h>

// Wire.begin() is called ONCE in setup() — shared with U8g2 (see §6).
extern TwoWire g_i2c;
SensirionI2CScd4x scd4x;

void Task_ReadSCD40(void* pvParam) {
    scd4x.begin(g_i2c);
    vTaskDelay(pdMS_TO_TICKS(1000));      // power-on → I2C ready

    scd4x.stopPeriodicMeasurement();
    vTaskDelay(pdMS_TO_TICKS(500));
    // scd4x.setTemperatureOffset(4.0);   // optional: ESP32 self-heating
    scd4x.startPeriodicMeasurement();     // 5 s cycle (SCD40 has NO single-shot mode)

    float co2Buf[5] = {0}, tBuf[5] = {0}, hBuf[5] = {0};
    int idx = 0;

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(5000));
        bool dataReady = false;
        scd4x.getDataReadyFlag(dataReady);
        if (!dataReady) continue;

        uint16_t co2Raw;
        float temperature, humidity;
        if (scd4x.readMeasurement(co2Raw, temperature, humidity) != 0 || co2Raw == 0) continue;

        co2Buf[idx] = co2Raw; tBuf[idx] = temperature; hBuf[idx] = humidity;
        idx = (idx + 1) % 5;

        float co2 = 0, t = 0, h = 0;
        for (int i = 0; i < 5; i++) { co2 += co2Buf[i]; t += tBuf[i]; h += hBuf[i]; }
        co2 /= 5.0f; t /= 5.0f; h /= 5.0f;

        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            g_sensorData.co2_ppm = co2;
            g_sensorData.temperature_c = t;
            g_sensorData.humidity_pct = h;
            g_sensorData.scd40_ready = true;
            xSemaphoreGive(g_dataMutex);
        }
    }
}
```

> **I2C bus ownership fix (V1 bug):** V1 called `Wire.begin()` inside `Task_ReadSCD40` while the U8g2 constructor initialised the same peripheral independently. Fix: call `Wire.begin(8, 9, 400000)` **once in `setup()`**, and lock the bus at 400 kHz (overclocking causes SCD40 CRC errors).

---

## 🖥️ 6. OLED 128×64 (U8g2 Full Framebuffer) — Values + Early Warning

The OLED is now a **warning channel**: the backend pushes `display_warning` commands with forecast-based messages *before* a threshold breach.

```text
┌──────────────────────────────────────┐
│ PM2.5 35.4     FAN:L2 ▓▓▓░░    ▲wifi │
│ CO2 620 ppm    T:29.5C  H:68.2%      │
├──────────────────────────────────────┤
│ ⚠ PM2.5 rising — breach ~01:00       │   ← pushed by backend (display_warning)
└──────────────────────────────────────┘
```

```cpp
#include <U8g2lib.h>

U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE, /*SCL*/ 9, /*SDA*/ 8);

void Task_UpdateOLED(void* pvParam) {
    u8g2.begin();
    u8g2.setContrast(0xCF);

    int8_t shift = 0; uint32_t lastShift = millis();

    for (;;) {
        SensorData_t d;
        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) { d = g_sensorData; xSemaphoreGive(g_dataMutex); }

        if (millis() - lastShift > 1800000) { shift ^= 1; lastShift = millis(); }

        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_helvR08_tr);

        char l1[32];
        snprintf(l1, sizeof(l1), "PM2.5 %.1f  FAN:%s L%u", d.pm25_ugm3,
                 d.fan_state ? "ON" : "OFF", d.fan_level);
        u8g2.drawStr(shift, 12, l1);

        char l2[32];
        snprintf(l2, sizeof(l2), "CO2 %dppm T:%.1fC H:%.0f%%",
                 (int)d.co2_ppm, d.temperature_c, d.humidity_pct);
        u8g2.drawStr(shift, 26, l2);

        u8g2.drawHLine(0, 32, 128);

        if (d.warning_active) {                       // Early warning from backend
            u8g2.setFont(u8g2_font_6x10_tr);
            u8g2.drawStr(shift, 46, "! EARLY WARNING");
            u8g2.drawStr(shift, 58, d.warning_msg);
        } else {
            u8g2.setFont(u8g2_font_6x10_tr);
            u8g2.drawStr(shift, 46, "Monitoring...");
        }

        if (d.mqtt_connected) u8g2.drawDisc(124, 60, 2); else u8g2.drawCircle(124, 60, 2);
        u8g2.sendBuffer();
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
```

---

## 🌀 7. Fan Control (NEW — the actuation path)

The fan is an **Intel 4-wire PWM fan**: pin 2 is fed a **constant +12 V**, and speed is set by a 25 kHz PWM signal on **pin 4** (PWM input — not a tachometer). PWMing the power line is **not** used.

### 7.1 Level → Duty Mapping

| Level (backend) | PWM duty | Meaning |
| :---: | :---: | :--- |
| 0 | 0 % | OFF |
| 1 | 35 % | Low |
| 2 | 60 % | Medium |
| 3 | 90 % | High |

Duty below ~20 % can stall a BLDC fan, hence discrete levels + a `min_duty` floor + a kickstart pulse.

### 7.2 Code

```cpp
#define FAN_PWM_PIN     5
#define FAN_PWM_CH      0
#define FAN_PWM_FREQ    25000   // 25 kHz — above audible range
#define FAN_PWM_RES     8       // 0-255
#define FAN_MIN_DUTY    51      // ≈20% — below this a BLDC fan may stall
#define FAN_KICKSTART_MS 200

static const uint8_t levelToDuty[4] = {0, 89, 153, 230};  // 0/35/60/90 %

volatile uint8_t  cmdLevel = 0;
volatile uint8_t  cmdMode  = 0;          // 0=AUTO 1=MANUAL
volatile bool     cmdState = false;
volatile uint32_t lastCommandMs = 0;

void fanApplyLevel(uint8_t level, bool on) {
    uint8_t duty = (on && level > 0) ? max((int)levelToDuty[level], (int)FAN_MIN_DUTY) : 0;

    if (on && duty > 0) {
        ledcWrite(FAN_PWM_CH, 255);          // kickstart pulse
        vTaskDelay(pdMS_TO_TICKS(FAN_KICKSTART_MS));
    }
    ledcWrite(FAN_PWM_CH, duty);

    if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
        g_sensorData.fan_level = level;
        g_sensorData.fan_duty_pct = (duty * 100) / 255;
        g_sensorData.fan_state = on && duty > 0;
        g_sensorData.fan_mode = cmdMode;
        xSemaphoreGive(g_dataMutex);
    }
}

void Task_FanControl(void* pvParam) {
    ledcSetup(FAN_PWM_CH, FAN_PWM_FREQ, FAN_PWM_RES);
    ledcAttachPin(FAN_PWM_PIN, FAN_PWM_CH);
    ledcWrite(FAN_PWM_CH, 0);                // ensure OFF at boot

    pinMode(6, INPUT_PULLUP);                // manual override button
    bool lastBtn = HIGH;

    for (;;) {
        // Physical button toggles MANUAL ON/OFF — always wins
        bool btn = digitalRead(6);
        if (lastBtn == HIGH && btn == LOW) {
            cmdMode = 1;
            cmdState = !cmdState;
            lastCommandMs = millis();
        }
        lastBtn = btn;

        fanApplyLevel(cmdLevel, cmdState);

        // Accumulate filter runtime while running
        if (g_sensorData.fan_state) {
            if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
                g_sensorData.filter_runtime_s++;
                xSemaphoreGive(g_dataMutex);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
```

---

## 🌐 8. Network & MQTT (Wi-Fi + NTP + MQTT + Actuation)

```cpp
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <time.h>

#define WIFI_SSID   "YourSSID"
#define WIFI_PASS   "YourPassword"
#define MQTT_SERVER "192.168.1.100"
#define MQTT_PORT   1883
#define MQTT_USER   "aqss_device"
#define MQTT_PASS   "device_secret"
#define DEVICE_ID   "AQSS_ESP32S3_01"
#define TOPIC_TELE    "aqss/devices/" DEVICE_ID "/telemetry"
#define TOPIC_CMD     "aqss/devices/" DEVICE_ID "/command"
#define TOPIC_STATE   "aqss/devices/" DEVICE_ID "/actuator/state"
#define TOPIC_STATUS  "aqss/devices/" DEVICE_ID "/status"
#define NTP_SERVER  "pool.ntp.org"
#define FAILSAFE_MS 600000UL     // 10 min without a command → fan OFF

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);
extern volatile uint8_t cmdLevel, cmdMode;
extern volatile bool cmdState;
extern volatile uint32_t lastCommandMs;

void Task_MQTT_Network(void* pvParam) {
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    WiFi.setAutoReconnect(true);
    while (WiFi.status() != WL_CONNECTED) vTaskDelay(pdMS_TO_TICKS(500));

    configTime(7 * 3600, 0, NTP_SERVER);              // GMT+7
    mqttClient.setServer(MQTT_SERVER, MQTT_PORT);
    mqttClient.setCallback(mqttCallback);
    mqttClient.setBufferSize(512);                    // V1 used 384 — overflows now

    uint32_t lastPublish = 0, lastNTP = 0, lastStatePub = 0;
    uint8_t lastPubState = 255;

    for (;;) {
        if (WiFi.status() != WL_CONNECTED) { WiFi.reconnect(); vTaskDelay(pdMS_TO_TICKS(5000)); continue; }

        if (!mqttClient.connected()) {
            String clientId = String(DEVICE_ID) + "_" + String(random(0xffff), HEX);
            // LWT: broker announces OFFLINE if we die ungracefully (V1 never published this topic)
            bool ok = mqttClient.connect(clientId.c_str(), MQTT_USER, MQTT_PASS,
                                         TOPIC_STATUS, 1, true, "{\"status\":\"OFFLINE\"}");
            if (ok) {
                mqttClient.publish(TOPIC_STATUS, "{\"status\":\"ONLINE\"}", true);
                mqttClient.subscribe(TOPIC_CMD);
                if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
                    g_sensorData.mqtt_connected = true; xSemaphoreGive(g_dataMutex);
                }
            }
        }

        mqttClient.loop();

        if (millis() - lastPublish >= 5000) { lastPublish = millis(); publishTelemetry(); }

        uint8_t s = g_sensorData.fan_state;
        if (s != lastPubState || (s && millis() - lastStatePub >= 5000)) {
            lastStatePub = millis(); lastPubState = s; publishActuatorState();
        }

        if (millis() - lastNTP >= 3600000) { configTime(7 * 3600, 0, NTP_SERVER); lastNTP = millis(); }

        // Fail-safe: backend brain silent for 10 min → stop the fan
        if (cmdState && millis() - lastCommandMs > FAILSAFE_MS && cmdMode == 0) {
            cmdState = false; cmdLevel = 0;
        }

        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            g_sensorData.wifi_rssi = WiFi.RSSI();
            g_sensorData.mqtt_connected = mqttClient.connected();
            g_sensorData.timestamp = (uint32_t)time(nullptr);
            g_sensorData.min_free_heap = ESP.getMinFreeHeap();
            xSemaphoreGive(g_dataMutex);
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void publishTelemetry() {
    SensorData_t d;
    if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) { d = g_sensorData; xSemaphoreGive(g_dataMutex); }

    JsonDocument doc;
    doc["device_id"] = DEVICE_ID;
    doc["space_id"]  = d.space_id;
    doc["timestamp"] = d.timestamp;

    JsonObject sensors = doc["sensors"].to<JsonObject>();
    sensors["pm25"]        = round(d.pm25_ugm3 * 10) / 10.0;
    sensors["co2"]         = d.co2_ppm;
    sensors["temperature"] = round(d.temperature_c * 10) / 10.0;
    sensors["humidity"]    = round(d.humidity_pct * 10) / 10.0;

    JsonObject system = doc["system"].to<JsonObject>();
    system["wifi_rssi"]     = d.wifi_rssi;
    system["uptime_sec"]    = millis() / 1000;
    system["free_heap"]     = ESP.getFreeHeap();
    system["min_free_heap"] = d.min_free_heap;

    char jsonBuffer[512];                       // V1 used 384 — too small now
    serializeJson(doc, jsonBuffer, sizeof(jsonBuffer));
    mqttClient.publish(TOPIC_TELE, jsonBuffer);
}

void publishActuatorState() {
    SensorData_t d;
    if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) { d = g_sensorData; xSemaphoreGive(g_dataMutex); }

    JsonDocument doc;
    doc["device_id"] = DEVICE_ID;
    doc["timestamp"] = d.timestamp;
    doc["state"]     = d.fan_state ? "ON" : "OFF";
    doc["level"]     = d.fan_level;
    doc["duty_pct"]  = d.fan_duty_pct;
    doc["mode"]      = d.fan_mode == 0 ? "AUTO" : "MANUAL";
    doc["filter_runtime_h"] = round((d.filter_runtime_s / 3600.0) * 10) / 10.0;

    char buf[256];
    serializeJson(doc, buf, sizeof(buf));
    mqttClient.publish(TOPIC_STATE, buf);
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
    JsonDocument doc;
    if (deserializeJson(doc, payload, length) != DeserializationError::Ok) return;

    // V1 BUG: `const char* cmd = doc["command"]; strcmp(cmd, ...)` crashes (nullptr)
    // when the field is missing. Always null-check first.
    const char* cmd = doc["command"] | (const char*)nullptr;
    if (cmd == nullptr) return;
    lastCommandMs = millis();

    if (strcmp(cmd, "reboot") == 0) {
        ESP.restart();
    } else if (strcmp(cmd, "set_actuator") == 0) {
        JsonObject p = doc["params"];
        cmdState = (p["state"] | "OFF") == String("ON");
        cmdLevel = p["level"] | 0;
        if (cmdLevel > 3) cmdLevel = 3;
        cmdMode = 0;                                    // backend command → AUTO
    } else if (strcmp(cmd, "display_warning") == 0) {
        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            g_sensorData.warning_active = true;
            strlcpy(g_sensorData.warning_msg, doc["params"]["message"] | "", sizeof(g_sensorData.warning_msg));
            xSemaphoreGive(g_dataMutex);
        }
    } else if (strcmp(cmd, "set_space") == 0) {
        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            strlcpy(g_sensorData.space_id, doc["params"]["space_id"] | "", sizeof(g_sensorData.space_id));
            xSemaphoreGive(g_dataMutex);
        }
        nvs.begin("aqss", false);
        nvs.putString("space_id", g_sensorData.space_id);
        nvs.end();
    } else if (strcmp(cmd, "recalibrate_dust") == 0) {
        float w0 = doc["params"]["baseline_w0"] | 1350.0f;
        nvs.begin("aqss", false);          // NVS — NOT EEPROM (ESP32-S3 has no EEPROM)
        nvs.putFloat("w0", w0);
        nvs.end();
    } else if (strcmp(cmd, "set_interval") == 0) {
        // adjust telemetry interval (persisted to NVS)
    } else if (strcmp(cmd, "force_ntp_sync") == 0) {
        configTime(7 * 3600, 0, NTP_SERVER);
    }
}
```

---

## 🔧 9. main.cpp — Entry Point

```cpp
#include <Arduino.h>
#include <Wire.h>

TwoWire g_i2c = Wire;   // ONE bus instance shared by SCD40 + OLED (V1 initialised it twice)

void Task_ReadSCD40(void*); void Task_ReadDustPWM(void*);
void Task_UpdateOLED(void*); void Task_MQTT_Network(void*); void Task_FanControl(void*);

extern SemaphoreHandle_t g_dataMutex;
extern SensorData_t g_sensorData;

void setup() {
    Serial.begin(115200);
    Serial.println("\n=== AQSS V2 — Integrated Node ===");

    g_dataMutex = xSemaphoreCreateMutex();
    memset(&g_sensorData, 0, sizeof(g_sensorData));
    strlcpy(g_sensorData.space_id, "SP_201", sizeof(g_sensorData.space_id));  // NVS default

    Wire.begin(8, 9, 400000);                 // single init, 400 kHz, shared bus

    xTaskCreatePinnedToCore(Task_ReadSCD40,    "SCD40",   4096, NULL, 2, NULL, 1);
    xTaskCreatePinnedToCore(Task_ReadDustPWM,  "DustPWM", 4096, NULL, 3, NULL, 1);
    xTaskCreatePinnedToCore(Task_FanControl,   "Fan",     3072, NULL, 3, NULL, 1);
    xTaskCreatePinnedToCore(Task_UpdateOLED,   "OLED",    4096, NULL, 1, NULL, 1);
    xTaskCreatePinnedToCore(Task_MQTT_Network, "MQTT",    8192, NULL, 2, NULL, 0);
}

void loop() { vTaskDelay(pdMS_TO_TICKS(10000)); }
```

---

## 🔋 10. Production Features

### 10.1 Watchdog — actually fed
```cpp
#include <esp_task_wdt.h>
// setup():  esp_task_wdt_init(30, true);
//           esp_task_wdt_add(NULL);          // subscribe each task
// each task loop (V1 said this but never did it):
esp_task_wdt_reset();
```

### 10.2 OTA
16 MB flash supports two OTA partitions (app0/app1) — `ArduinoOTA` or `HTTPUpdate` over Wi-Fi.

### 10.3 Offline buffer
On MQTT loss, cache up to 60 telemetry records (5 min) in a ring buffer in PSRAM and batch-upload on reconnect, preserving the original `timestamp` so the backend can back-fill monthly partitions.

### 10.4 Fail-safe
No backend command for 10 minutes in AUTO mode → fan OFF (§7.2 / §8). The physical button always works.

---

## ✅ 11. Pre-Deployment Verification Checklist

- [ ] **LM2596 adjusted to exactly 5.0 V with a multimeter BEFORE connecting the ESP32.**
- [ ] OLED silkscreen (GND-VCC vs VCC-GND) checked before power-on.
- [ ] 1kΩ/2kΩ divider on GP2Y1023 Vo → GPIO 18 verified.
- [ ] RC filter (150Ω + 220µF) on GP2Y1023 V_LED verified.
- [ ] SCD40 Module correctly powered from 3.3V (not 5V).
- [ ] I2C scanner sees both `0x3C` (OLED) and `0x62` (SCD40) at 400 kHz.
- [ ] Fan: 4 wires identified with a multimeter; pin 2 on +12 V **via fuse**; pin 4 to GPIO 5; common GND verified.
- [ ] Fan spins at levels 1/2/3 and **stays off at boot** (pulldown/boot bias verified).
- [ ] Fan kickstart works from a dead stop; `min_duty` found and enforced.
- [ ] LWT verified: kill the node → broker publishes `OFFLINE` on the status topic.
- [ ] `set_actuator` / `display_warning` / `set_space` / `recalibrate_dust` handled without crashing on malformed JSON.
- [ ] GP2Y1023 baseline W₀ calibrated in clean air and persisted to NVS.
- [ ] Fail-safe verified: stop the backend → fan turns OFF after 10 min.

---

## 📁 12. Source Code Directory Structure

```text
embedded/
├── include/
│   ├── config.h           # WiFi, MQTT, pin map, device/space IDs
│   ├── sensor_data.h      # SensorData_t struct & mutex
│   ├── dust_sensor.h      # GP2Y1023AU0F task
│   ├── scd40_sensor.h     # SCD40 task
│   ├── oled_display.h     # OLED task (values + warnings)
│   ├── fan_control.h      # Fan PWM task (NEW)
│   └── mqtt_network.h     # WiFi + NTP + MQTT + commands
├── src/
│   ├── main.cpp           # Entry point, single Wire init, task creation
│   ├── dust_sensor.cpp
│   ├── scd40_sensor.cpp
│   ├── oled_display.cpp
│   ├── fan_control.cpp    # LEDC PWM, kickstart, min_duty, button (NEW)
│   └── mqtt_network.cpp   # telemetry + actuator/state + status/LWT + callbacks
└── platformio.ini
```

> Removed from V1: `aqi_calculator.h/.cpp` — AQI is a backend-side NowCast metric (display only).
