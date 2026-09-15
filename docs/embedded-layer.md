# Detailed Plan — Layer 1: Embedded System Layer

> **Version:** 2.1 (Translated to English, aligned with `embedded-engineer` and `cpp-platformIO-pro` subagent definitions)
> **Reference:** [hardware_datasheet_reference.md](./hardware_datasheet_reference.md)

---

## 📋 1. Hardware Components & Key Specifications

| # | Component | Model | Interface | Power | I2C Addr |
| :---: | :--- | :--- | :---: | :---: | :---: |
| 1 | Microcontroller | ESP32-S3-DevKitC-1 (N16R8) | — | 5V USB | — |
| 2 | CO2/Temp/Hum Sensor | Sensirion SCD40 | **I2C** | 2.4–5.5V | **0x62** |
| 3 | PM2.5 Dust Sensor | Sharp GP2Y1023AU0F | **PWM Digital** | 5V | — |
| 4 | OLED Display | SSD1306 128×64 0.96" | **I2C** | 3.3V | **0x3C** |

---

## 🔌 2. Hardware Wiring Diagram

```text
                    ┌──────────────────────────────┐
                    │     ESP32-S3-DevKitC-1        │
                    │          (N16R8)               │
                    │                                │
    I2C Bus ◄───────┤ GPIO 8 (SDA) ─────────────────┤──── SCD40 SDA (Pin 4)
    (400 kHz)       │ GPIO 9 (SCL) ─────────────────┤──── SCD40 SCL (Pin 5)
                    │                                │──── OLED SDA (Pin 4)
                    │                    ────────────┤──── OLED SCL (Pin 3)
                    │                                │
                    │ GPIO 18 (Input) ◄──[Divider]───┤──── GP2Y1023 Vo (Pin 1)
                    │                                │
                    │ 3V3 ──────────────────────────┤──── SCD40 VDD (Pin 1)
                    │                   ────────────┤──── OLED VCC (Pin 2)
                    │                                │
                    │ 5V  ──────────────────────────┤──── GP2Y1023 VCC (Pin 5)
                    │               ──[150Ω]────────┤──── GP2Y1023 V_LED (Pin 4)
                    │                                │
                    │ GND ──────────────────────────┤──── All GNDs (Star Ground)
                    └──────────────────────────────┘

    ┌─────────────────────────────────────────────────────┐
    │ VOLTAGE DIVIDER CIRCUIT for GP2Y1023 Vo             │
    │                                                     │
    │  GP2Y1023 Vo ──── R1=1.0kΩ ──── GPIO 18 ──── R2=2.0kΩ ──── GND │
    │                                                     │
    │  HIGH: 5V × 2.0/(1.0+2.0) = 3.33V ✅              │
    │  LOW:  < 0.8V ✅                                    │
    └─────────────────────────────────────────────────────┘

    ┌─────────────────────────────────────────────────────┐
    │ RC CIRCUIT for GP2Y1023 V_LED                       │
    │                                                     │
    │  +5V ──── R=150Ω ──── V_LED (Pin 4)                │
    │                            │                        │
    │                        C=220µF (Electrolytic)       │
    │                            │                        │
    │                       LED-GND (Pin 2)               │
    └─────────────────────────────────────────────────────┘
```

### Detailed Pin Connection Table

| Device | Device Pin | ESP32-S3 Pin | Protocol | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **SCD40** | Pin 1 (VDD) | 3V3 | Power | + 100nF & 10µF decoupling caps close to pin |
| | Pin 2 (VDDH) | Connect to VDD | Power | **Must be connected to VDD on PCB** |
| | Pin 3 (GND) | GND | Power | |
| | Pin 4 (SDA) | **GPIO 8** | I2C Data | Pull-up on board module |
| | Pin 5 (SCL) | **GPIO 9** | I2C Clock | Pull-up on board module |
| **OLED SSD1306** | Pin 1 (GND) | GND | Power | ⚠️ Check silkscreen carefully! |
| | Pin 2 (VCC) | 3V3 | Power | Module has 3.3V LDO onboard |
| | Pin 3 (SCL) | **GPIO 9** | I2C Clock | Shared bus with SCD40 |
| | Pin 4 (SDA) | **GPIO 8** | I2C Data | Shared bus with SCD40 |
| **GP2Y1023AU0F** | Pin 1 (Vo) | **GPIO 18** (via divider) | PWM Input | R1=1kΩ + R2=2kΩ voltage divider |
| | Pin 2 (LED-GND) | GND | Power | Star ground, isolated |
| | Pin 3 (S-GND) | GND | Power | Connect to shield/casing |
| | Pin 4 (V_LED) | 5V via 150Ω | Power | + 220µF capacitor to LED-GND |
| | Pin 5 (VCC) | 5V | Power | Regulated 5V ±5% |

### Reasons for Choosing GPIO 18 for GP2Y1023:
- **Not a Strapping pin** (Avoid GPIO 0, 3, 45, 46).
- **Not used for USB** (GPIO 19, 20 are reserved for USB D−/D+).
- **Not blocked by Octal PSRAM** (GPIO 33–37 are locked on N16R8).
- **ADC2 Group** (GPIO 18 = ADC2_CH7) but we use it as a digital input, not ADC → **not affected by Wi-Fi**.
- Supports **Interrupts (attachInterrupt)** and **PCNT** via the GPIO Matrix.

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
| `U8g2` | Latest | OLED SSD1306 128×64 driver (Full framebuffer, UTF-8) |
| `WiFi` | Built-in | 2.4 GHz Wi-Fi connectivity |
| `PubSubClient` | Latest | Lightweight MQTT Client |
| `ArduinoJson` | v7.x | Serialize/Deserialize JSON payloads |
| `NTPClient` / `time.h` | Built-in | NTP time synchronization |

### 3.3 FreeRTOS Task Allocation (Dual-Core)

```text
┌─── Core 0 (PRO_CPU) ──────────────────────────────────────────┐
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ [System Tasks - Automatic]                              │   │
│  │  • wifi_task (Wi-Fi driver)                             │   │
│  │  • lwIP tcp/ip thread                                   │   │
│  │  • BLE controller (if used)                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Task_MQTT_Network (Priority 2, Stack 8192)              │   │
│  │  • Maintain Wi-Fi connection (auto-reconnect)           │   │
│  │  • NTP sync every 1 hour                                │   │
│  │  • MQTT connect / publish / subscribe                   │   │
│  │  • Publish telemetry JSON every 5 seconds               │   │
│  │  • Handle server commands (subscribe topic)             │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘

┌─── Core 1 (APP_CPU) ──────────────────────────────────────────┐
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Task_ReadSCD40 (Priority 2, Stack 4096)                 │   │
│  │  • Call scd4x.readMeasurement() every 5 seconds         │   │
│  │  • Check data ready flag before reading                 │   │
│  │  • Apply Moving Average (N=5) for CO2, Temp, Hum        │   │
│  │  • Write to shared struct (mutex-protected)             │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Task_ReadDustPWM (Priority 3, Stack 4096)               │   │
│  │  • Interrupt-based pulse width measurement              │   │
│  │  • Rolling average ~100 samples/sec → 1 value/sec       │   │
│  │  • Subtract baseline W₀, convert to µg/m³               │   │
│  │  • Filter outliers (drop pulse < 6µs or > 7500µs)       │   │
│  │  • Write to shared struct (mutex-protected)             │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Task_UpdateOLED (Priority 1, Stack 4096)                │   │
│  │  • Read from shared struct                              │   │
│  │  • Render 128×64 OLED UI (U8g2 Full Buffer)             │   │
│  │  • Update every 1 second                                │   │
│  │  • Display Wi-Fi/MQTT status icons                      │   │
│  │  • Anti burn-in: pixel shift ±1px every 30 mins         │   │
│  │  • Auto-sleep after 3 mins of no value change           │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

### 3.4 Shared Data Structure & Thread Safety

```cpp
// Shared sensor data struct across Tasks (mutex-protected)
typedef struct {
    char  space_id[32];      // Space ID the device belongs to
    bool  warning_active;    // True if early warning is active
    char  warning_msg[64];   // Warning message from backend
    
    // SCD40 data
    float co2_ppm;           // CO2 (ppm), range 400-2000
    float temperature_c;     // Temperature (°C)
    float humidity_pct;      // Humidity (%)
    bool  scd40_ready;       // SCD40 read success flag
    
    // GP2Y1023AU0F data  
    float pm25_ugm3;         // PM2.5 Dust (µg/m³)
    float dust_pulse_us;     // Average pulse width (µs) — debug
    bool  dust_ready;        // Dust sensor stabilized flag
    
    // Computed
    int   aqi_index;         // AQI calculated from PM2.5
    char  aqi_level[16];     // "Good", "Moderate", "Unhealthy", etc.
    
    // System
    int   wifi_rssi;         // Wi-Fi signal strength (dBm)
    bool  mqtt_connected;    // MQTT connection status
    uint32_t timestamp;      // Unix timestamp (NTP)
} SensorData_t;

SensorData_t g_sensorData;
SemaphoreHandle_t g_dataMutex;
```

### 3.5 MQTT Telemetry Payload (JSON)

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "space_id": "space_201",
  "timestamp": 1724217600,
  "sensors": {
    "pm25": 35.4,
    "co2": 620,
    "temperature": 29.5,
    "humidity": 68.2
  },
  "computed": {
    "aqi_index": 101,
    "aqi_level": "Unhealthy"
  },
  "system": {
    "wifi_rssi": -65,
    "uptime_sec": 3600,
    "free_heap": 245760
  }
}
```

**MQTT Topics:**
- **Publish:** `aqss/devices/AQSS_ESP32S3_01/telemetry` (QoS 1, every 5 seconds)
- **Subscribe:** `aqss/devices/AQSS_ESP32S3_01/command` (Receive commands: reboot, recalibrate, set interval...)

---

## 🧪 4. GP2Y1023AU0F Reading Method (PWM Interrupt-Based)

> **⚠️ GP2Y1023AU0F ≠ GP2Y1010AU0F.** This sensor has an integrated MCU, triggers its own IR LED, and outputs digital PWM. NO NEED to manually pulse the LED, NEVER use `analogRead()`.

### 4.1 Operating Principle

```text
     GP2Y1023AU0F Internal MCU
     ┌──────────────────────────────────────┐
     │  Self-triggers IR LED (100 Hz)       │
     │  Self-reads phototransistor (10-bit) │
     │  Converts to PWM output              │
     └──────────────────────┬───────────────┘
                            │ VOUT (PWM)
                            ▼
     ┌─────────────────────────────────────────────────────────┐
     │  PWM Signal (T = 10ms, f = 100Hz):                      │
     │                                                         │
     │   ┌──── W (High) ────┐                                  │
     │   │                  │                                  │
     │ ──┘                  └──────────────── (Low) ──────── ──│
     │   |<--- W (µs) ----->|<-- (T - W) -->|                  │
     │                                                         │
     │  W is directly proportional to dust concentration:      │
     │  Dust (µg/m³) = (W - W₀) / 14.0                         │
     └─────────────────────────────────────────────────────────┘
```

### 4.2 Reading Code (Interrupt-Based, Non-Blocking, Production-Ready)

```cpp
// ===== GP2Y1023AU0F PWM Reader =====
#define DUST_PWM_PIN          18      // GPIO 18 (via 1kΩ/2kΩ voltage divider)
#define DUST_SENSITIVITY      14.0f   // µs per µg/m³
#define DUST_BASELINE_W0      1350    // µs — calibrate in clean air
#define DUST_SAMPLE_WINDOW_MS 1000    // Average window (1 second)
#define DUST_PULSE_MIN_US     6       // Minimum valid pulse width
#define DUST_PULSE_MAX_US     7500    // Maximum valid pulse width

volatile uint32_t _dustPulseStart = 0;
volatile uint32_t _dustPulseWidth = 0;
volatile bool     _dustNewPulse   = false;

void IRAM_ATTR dustPWM_ISR() {
    uint32_t now = micros();
    if (digitalRead(DUST_PWM_PIN) == HIGH) {
        _dustPulseStart = now;
    } else {
        if (_dustPulseStart > 0) {
            _dustPulseWidth = now - _dustPulseStart;
            _dustNewPulse = true;
        }
    }
}

void Task_ReadDustPWM(void* pvParam) {
    pinMode(DUST_PWM_PIN, INPUT);
    
    // Wait 1.5s for GP2Y1023 to boot (1s HIGH startup)
    vTaskDelay(pdMS_TO_TICKS(1500));
    
    attachInterrupt(digitalPinToInterrupt(DUST_PWM_PIN), dustPWM_ISR, CHANGE);
    
    float sumPulse = 0;
    int sampleCount = 0;
    uint32_t lastCalc = millis();
    
    for (;;) {
        if (_dustNewPulse) {
            _dustNewPulse = false;
            uint32_t pw = _dustPulseWidth;
            
            // Filter valid pulses
            if (pw >= DUST_PULSE_MIN_US && pw <= DUST_PULSE_MAX_US) {
                sumPulse += pw;
                sampleCount++;
            }
        }
        
        // Calculate average every 1 second (~100 samples)
        if (millis() - lastCalc >= DUST_SAMPLE_WINDOW_MS && sampleCount > 0) {
            float avgPulse = sumPulse / sampleCount;
            sumPulse = 0;
            sampleCount = 0;
            lastCalc = millis();
            
            float deltaPulse = avgPulse - DUST_BASELINE_W0;
            if (deltaPulse < 0) deltaPulse = 0;
            float pm25 = deltaPulse / DUST_SENSITIVITY;
            
            // Write to shared struct
            if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
                g_sensorData.pm25_ugm3 = pm25;
                g_sensorData.dust_pulse_us = avgPulse;
                g_sensorData.dust_ready = true;
                // Calculate AQI from PM2.5
                g_sensorData.aqi_index = calculateAQI_PM25(pm25);
                getAQILevel(g_sensorData.aqi_index, g_sensorData.aqi_level);
                xSemaphoreGive(g_dataMutex);
            }
        }
        
        vTaskDelay(pdMS_TO_TICKS(1)); // Yield CPU
    }
}
```

---

## 📡 5. SCD40 Reading (I2C — Official Sensirion Library)

```cpp
#include <SensirionI2CScd4x.h>
#include <Wire.h>

SensirionI2CScd4x scd4x;

void Task_ReadSCD40(void* pvParam) {
    // Init I2C
    Wire.begin(8, 9, 400000); // SDA=GPIO8, SCL=GPIO9, 400kHz
    scd4x.begin(Wire);
    
    // Wait 1 second for SCD40 I2C to be ready
    vTaskDelay(pdMS_TO_TICKS(1000));
    
    // Stop any ongoing measurements
    scd4x.stopPeriodicMeasurement();
    vTaskDelay(pdMS_TO_TICKS(500));
    
    // (Optional) Set Temperature Offset to compensate for ESP32 heat
    // Default factory = 4.0°C, adjust as needed
    // scd4x.setTemperatureOffset(4.0);
    
    // (Optional) Set altitude compensation
    // scd4x.setSensorAltitude(10);
    
    // Start periodic measurement (every 5 seconds)
    scd4x.startPeriodicMeasurement();
    
    // Moving Average buffers
    float co2Buffer[5] = {0};
    float tempBuffer[5] = {0};
    float humBuffer[5] = {0};
    int bufIdx = 0;
    
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(5000)); // Wait 5 seconds (SCD40 cycle)
        
        // Check data ready flag
        bool dataReady = false;
        scd4x.getDataReadyFlag(dataReady);
        if (!dataReady) continue;
        
        uint16_t co2Raw;
        float temperature, humidity;
        uint16_t error = scd4x.readMeasurement(co2Raw, temperature, humidity);
        
        if (error != 0 || co2Raw == 0) continue; // Skip on error
        
        // Moving Average
        co2Buffer[bufIdx] = (float)co2Raw;
        tempBuffer[bufIdx] = temperature;
        humBuffer[bufIdx] = humidity;
        bufIdx = (bufIdx + 1) % 5;
        
        float avgCO2 = 0, avgTemp = 0, avgHum = 0;
        for (int i = 0; i < 5; i++) {
            avgCO2 += co2Buffer[i];
            avgTemp += tempBuffer[i];
            avgHum += humBuffer[i];
        }
        avgCO2 /= 5.0f; avgTemp /= 5.0f; avgHum /= 5.0f;
        
        // Write to shared struct
        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            g_sensorData.co2_ppm = avgCO2;
            g_sensorData.temperature_c = avgTemp;
            g_sensorData.humidity_pct = avgHum;
            g_sensorData.scd40_ready = true;
            xSemaphoreGive(g_dataMutex);
        }
    }
}
```

---

## 🖥️ 6. OLED 128x64 Interface (U8g2 Full Framebuffer)

### Screen Layout

```text
┌──────────────────────────────────────┐ 128px
│ AQI:101 [▓▓▓▓▓░░░░░]    ▲ 10:24│   Row 0-15  (16px)
├──────────────────────────────────────┤
│ PM2.5    35.4 µg/m³     [UnH]  │   Row 16-31 (16px)
│ CO2      620  ppm       [OK]   │   Row 32-47 (16px)
│ Temp  29.5°C  Hum  68.2%      │   Row 48-63 (16px)
└──────────────────────────────────────┘
  ▲ = Wi-Fi icon    [UnH] = AQI Level badge
```

### OLED Render Code

```cpp
#include <U8g2lib.h>

#define I2C_SDA 8
#define I2C_SCL 9

U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(
    U8G2_R0, U8X8_PIN_NONE, I2C_SCL, I2C_SDA
);

void Task_UpdateOLED(void* pvParam) {
    u8g2.begin();
    u8g2.enableUTF8Print();
    u8g2.setContrast(0xCF); // High contrast
    
    int8_t pixelShiftX = 0; // Anti burn-in
    uint32_t lastShift = millis();
    
    for (;;) {
        SensorData_t data;
        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            data = g_sensorData; // Copy snapshot
            xSemaphoreGive(g_dataMutex);
        }
        
        // Anti burn-in: shift ±1px every 30 mins
        if (millis() - lastShift > 1800000) {
            pixelShiftX = (pixelShiftX == 0) ? 1 : 0;
            lastShift = millis();
        }
        
        u8g2.clearBuffer();
        int ox = pixelShiftX; // X Offset
        
        // === Header: AQI Gauge + Wi-Fi Icon ===
        u8g2.setFont(u8g2_font_helvB10_tr);
        char aqiBuf[16];
        snprintf(aqiBuf, sizeof(aqiBuf), "AQI:%d", data.aqi_index);
        u8g2.drawStr(ox, 13, aqiBuf);
        
        // AQI Progress Bar (50px wide)
        u8g2.drawFrame(ox + 52, 3, 50, 10);
        int barWidth = constrain(data.aqi_index * 48 / 500, 0, 48);
        u8g2.drawBox(ox + 53, 4, barWidth, 8);
        
        // Wi-Fi icon (simple triangle)
        if (data.wifi_rssi < 0) {
            u8g2.drawTriangle(ox+118, 12, ox+122, 2, ox+126, 12);
        }
        
        u8g2.drawHLine(0, 16, 128); // Separator
        
        // === Body: Sensor Values ===
        u8g2.setFont(u8g2_font_helvR08_tr);
        
        if (data.warning_active) {
            u8g2.drawStr(ox, 29, "WARNING!");
            u8g2.drawStr(ox, 43, data.warning_msg);
        } else {
            char line1[32], line2[32], line3[32];
            snprintf(line1, sizeof(line1), "PM2.5  %.1f ug/m3", data.pm25_ugm3);
            snprintf(line2, sizeof(line2), "CO2    %d ppm", (int)data.co2_ppm);
            snprintf(line3, sizeof(line3), "T:%.1fC  H:%.1f%%", 
                     data.temperature_c, data.humidity_pct);
            
            u8g2.drawStr(ox, 29, line1);
            u8g2.drawStr(ox + 100, 29, data.aqi_level);
            u8g2.drawStr(ox, 43, line2);
            u8g2.drawStr(ox, 60, line3);
        }
        
        // MQTT status dot
        if (data.mqtt_connected) {
            u8g2.drawDisc(ox + 124, 58, 2); // Filled circle = connected
        } else {
            u8g2.drawCircle(ox + 124, 58, 2); // Empty circle = disconnected
        }
        
        u8g2.sendBuffer(); // Takes ~23ms @ 400kHz
        
        vTaskDelay(pdMS_TO_TICKS(1000)); // Update every 1 second
    }
}
```

---

## 🌐 7. Network & MQTT Connection (Wi-Fi + NTP + MQTT)

```cpp
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <time.h>

#define WIFI_SSID     "YourSSID"
#define WIFI_PASS     "YourPassword"
#define MQTT_SERVER   "192.168.1.100"   // Or MQTT broker domain
#define MQTT_PORT     1883
#define MQTT_USER     "aqss_device"
#define MQTT_PASS     "device_secret"
#define DEVICE_ID     "AQSS_ESP32S3_01"
#define TOPIC_TELE    "aqss/devices/" DEVICE_ID "/telemetry"
#define TOPIC_CMD     "aqss/devices/" DEVICE_ID "/command"
#define NTP_SERVER    "pool.ntp.org"

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

void Task_MQTT_Network(void* pvParam) {
    // Connect Wi-Fi
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    WiFi.setAutoReconnect(true);
    
    while (WiFi.status() != WL_CONNECTED) {
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    
    // Sync NTP
    configTime(7 * 3600, 0, NTP_SERVER); // GMT+7
    
    // Configure MQTT
    mqttClient.setServer(MQTT_SERVER, MQTT_PORT);
    mqttClient.setCallback(mqttCallback);
    mqttClient.setBufferSize(512);
    
    uint32_t lastPublish = 0;
    uint32_t lastNTPSync = 0;
    
    for (;;) {
        // Auto-reconnect Wi-Fi
        if (WiFi.status() != WL_CONNECTED) {
            WiFi.reconnect();
            vTaskDelay(pdMS_TO_TICKS(5000));
            continue;
        }
        
        // Auto-reconnect MQTT
        if (!mqttClient.connected()) {
            String clientId = String(DEVICE_ID) + "_" + String(random(0xffff), HEX);
            const char* willTopic = "aqss/devices/" DEVICE_ID "/status";
            const char* willPayload = "{\"status\":\"OFFLINE\"}";
            if (mqttClient.connect(clientId.c_str(), MQTT_USER, MQTT_PASS, willTopic, 1, true, willPayload)) {
                mqttClient.publish(willTopic, "{\"status\":\"ONLINE\"}", true);
                mqttClient.subscribe(TOPIC_CMD);
                if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
                    g_sensorData.mqtt_connected = true;
                    xSemaphoreGive(g_dataMutex);
                }
            }
        }
        
        mqttClient.loop();
        
        // Publish telemetry every 5 seconds
        if (millis() - lastPublish >= 5000) {
            lastPublish = millis();
            publishTelemetry();
        }
        
        // Re-sync NTP every 1 hour
        if (millis() - lastNTPSync >= 3600000) {
            configTime(7 * 3600, 0, NTP_SERVER);
            lastNTPSync = millis();
        }
        
        // Update System Status
        if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
            g_sensorData.wifi_rssi = WiFi.RSSI();
            g_sensorData.mqtt_connected = mqttClient.connected();
            g_sensorData.timestamp = (uint32_t)time(nullptr);
            xSemaphoreGive(g_dataMutex);
        }
        
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void publishTelemetry() {
    SensorData_t data;
    if (xSemaphoreTake(g_dataMutex, pdMS_TO_TICKS(10))) {
        data = g_sensorData;
        xSemaphoreGive(g_dataMutex);
    }
    
    JsonDocument doc;
    doc["device_id"] = DEVICE_ID;
    doc["space_id"] = data.space_id;  // V2: Space association
    doc["timestamp"] = data.timestamp;
    
    JsonObject sensors = doc["sensors"].to<JsonObject>();
    sensors["pm25"] = round(data.pm25_ugm3 * 10) / 10.0;
    sensors["co2"] = (int)data.co2_ppm;
    sensors["temperature"] = round(data.temperature_c * 10) / 10.0;
    sensors["humidity"] = round(data.humidity_pct * 10) / 10.0;
    
    JsonObject computed = doc["computed"].to<JsonObject>();
    computed["aqi_index"] = data.aqi_index;
    computed["aqi_level"] = data.aqi_level;
    
    JsonObject system = doc["system"].to<JsonObject>();
    system["wifi_rssi"] = data.wifi_rssi;
    system["uptime_sec"] = millis() / 1000;
    system["free_heap"] = ESP.getFreeHeap();
    
    char jsonBuffer[384];
    serializeJson(doc, jsonBuffer, sizeof(jsonBuffer));
    
    mqttClient.publish(TOPIC_TELE, jsonBuffer);
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
    // Handle incoming commands from server
    JsonDocument doc;
    deserializeJson(doc, payload, length);
    
    const char* cmd = doc["command"];
    if (strcmp(cmd, "reboot") == 0) {
        ESP.restart();
    }
    // Handle other commands...
}
```

---

## 🔧 8. main.cpp — Entry Point

```cpp
#include <Arduino.h>

// Forward declarations
void Task_ReadSCD40(void* pvParam);
void Task_ReadDustPWM(void* pvParam);
void Task_UpdateOLED(void* pvParam);
void Task_MQTT_Network(void* pvParam);

extern SemaphoreHandle_t g_dataMutex;
extern SensorData_t g_sensorData;

void setup() {
    Serial.begin(115200);
    Serial.println("\n=== AQSS - Air Quality Supervision System ===");
    Serial.println("ESP32-S3 | SCD40 | GP2Y1023AU0F | OLED 0.96\"");
    
    // Initialize mutex
    g_dataMutex = xSemaphoreCreateMutex();
    memset(&g_sensorData, 0, sizeof(g_sensorData));
    
    // Create FreeRTOS Tasks
    xTaskCreatePinnedToCore(Task_ReadSCD40,    "SCD40",   4096, NULL, 2, NULL, 1);
    xTaskCreatePinnedToCore(Task_ReadDustPWM,  "DustPWM", 4096, NULL, 3, NULL, 1);
    xTaskCreatePinnedToCore(Task_UpdateOLED,   "OLED",    4096, NULL, 1, NULL, 1);
    xTaskCreatePinnedToCore(Task_MQTT_Network, "MQTT",    8192, NULL, 2, NULL, 0);
    
    Serial.println("All tasks started successfully.");
}

void loop() {
    vTaskDelay(pdMS_TO_TICKS(10000)); // Main loop idle
}
```

---

## 🔋 9. Additional Features (Production-Ready)

### 9.1 Watchdog Timer (WDT)
```cpp
#include <esp_task_wdt.h>
// In setup():
esp_task_wdt_init(30, true); // 30 seconds timeout, auto-reboot
// In each task loop:
esp_task_wdt_reset(); // Feed watchdog
```

### 9.2 OTA (Over-The-Air) Update
- ESP32-S3 with 16MB Flash supports OTA 2 partitions (app0 + app1).
- Use `ArduinoOTA` or `HTTPUpdate` for remote firmware updates via Wi-Fi without USB.

### 9.3 NTP Time Sync
- Integrated in `Task_MQTT_Network`.
- Timezone configured via `configTime()`.

### 9.4 Offline Data Buffer
- When MQTT disconnects, cache up to 60 records (5 minutes of data) into a ring buffer.
- Batch upload all cached data when the connection recovers.

---

## V2 — Actuator Node Firmware

The V2 architecture introduces Actuator Nodes (e.g., relays controlling air filters) alongside Sensor Nodes.
- Reuses the ESP32-S3 hardware platform.
- Subscribes to `aqss/devices/{id}/command` for `set_actuator` commands.
- Controls a relay module (GPIO output) connected to the air filter power line.
- Publishes actuator state to `aqss/devices/{id}/actuator/state` every 5 seconds.
- Physical manual override button (GPIO input) — always takes priority.
- Fail-safe: if no command received for 10 minutes, turn OFF relay.
- Watchdog timer: 30 seconds, auto-reboot on hang.

---

## ✅ 10. Pre-Deployment Verification Checklist

- [ ] Check OLED silkscreen (GND-VCC vs VCC-GND) before powering on.
- [ ] Verify 1kΩ/2kΩ voltage divider on GP2Y1023 Vo → GPIO 18 wire.
- [ ] Verify RC filter (150Ω + 220µF) on GP2Y1023 V_LED.
- [ ] SCD40: VDD and VDDH tied together, 100nF + 10µF caps close to pins.
- [ ] Run I2C Scanner to confirm 2 devices: 0x3C (OLED) and 0x62 (SCD40).
- [ ] GP2Y1023: Wait 1.5 seconds after power-on before reading PWM.
- [ ] SCD40: Wait 5+ seconds after `start_periodic` before reading.
- [ ] Monitor Serial output to debug individual sensors.
- [ ] Calibrate GP2Y1023 baseline W₀ in clean air.

---

## 📁 11. Source Code Directory Structure

```text
embedded/
├── include/
│   ├── config.h              # WiFi, MQTT, Pin definitions
│   ├── sensor_data.h         # SensorData_t struct & mutex
│   ├── dust_sensor.h         # GP2Y1023AU0F task header
│   ├── scd40_sensor.h        # SCD40 task header
│   ├── oled_display.h        # OLED task header
│   ├── mqtt_network.h        # MQTT + WiFi task header
│   └── aqi_calculator.h      # AQI calculation functions
├── src/
│   ├── main.cpp              # Entry point, task creation
│   ├── dust_sensor.cpp       # GP2Y1023AU0F PWM interrupt reader
│   ├── scd40_sensor.cpp      # SCD40 I2C periodic reader
│   ├── oled_display.cpp      # U8g2 OLED rendering
│   ├── mqtt_network.cpp      # WiFi + NTP + MQTT + JSON publish
│   └── aqi_calculator.cpp    # AQI breakpoint calculation
└── platformio.ini            # PlatformIO project config
```
