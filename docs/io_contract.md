# AQSS — Input / Output Contract Declaration

> **Purpose:** Single source of truth for all data contracts between the 4 layers of the Air Quality Supervision System. Use this document to validate integration tests, mock data generators, and API conformance checks.
>
> **Last Validated:** 2026-08-21 (post cross-layer audit)

---

## Table of Contents

1. [Layer 1 → Layer 2: MQTT Telemetry](#1-layer-1--layer-2-mqtt-telemetry)
2. [Layer 2 → Layer 1: MQTT Commands](#2-layer-2--layer-1-mqtt-commands)
3. [Layer 2 → Database: PostgreSQL Schema](#3-layer-2--database-postgresql-schema)
4. [Layer 2 → Layer 4: REST API Responses](#4-layer-2--layer-4-rest-api-responses)
5. [Layer 2 → Layer 4: STOMP WebSocket Messages](#5-layer-2--layer-4-stomp-websocket-messages)
6. [Layer 2 → Layer 3: DL Prediction Request](#6-layer-2--layer-3-dl-prediction-request)
7. [Layer 3 → Layer 2: DL Prediction Response](#7-layer-3--layer-2-dl-prediction-response)
8. [Layer 2 → Layer 3: DL Evaluation Request](#8-layer-2--layer-3-dl-evaluation-request)
9. [Layer 3 → Layer 2: DL Evaluation Response](#9-layer-3--layer-2-dl-evaluation-response)
10. [Layer 4 → Layer 2: REST API Requests](#10-layer-4--layer-2-rest-api-requests)
11. [Sensor Value Ranges & Validation Rules](#11-sensor-value-ranges--validation-rules)
12. [AQI Breakpoint Table (EPA Standard)](#12-aqi-breakpoint-table-epa-standard)

---

## 1. Layer 1 → Layer 2: MQTT Telemetry

| Property | Value |
| :--- | :--- |
| **Protocol** | MQTT v3.1.1 |
| **Broker** | Eclipse Mosquitto (port 1883) |
| **Topic** | `aqss/devices/{device_id}/telemetry` |
| **QoS** | 1 (At Least Once) |
| **Publish Interval** | Every **5 seconds** |
| **Auth** | Username/Password (defined in `mosquitto.conf`) |
| **Payload Format** | JSON (UTF-8) |
| **Max Payload Size** | 384 bytes |

### Payload Schema

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "timestamp": 1724217600,
  "sensors": {
    "pm25": 35.4,
    "co2": 620,
    "temperature": 29.5,
    "humidity": 68.2
  },
  "computed": {
    "aqi_index": 101,
    "aqi_level": "Unhealthy for Sensitive Groups"
  },
  "system": {
    "wifi_rssi": -65,
    "uptime_sec": 3600,
    "free_heap": 245760
  }
}
```

### Field Definitions

| JSON Path | Type | Unit | Range | Nullable | Source |
| :--- | :---: | :---: | :--- | :---: | :--- |
| `device_id` | `string` | — | Max 32 chars, pattern `AQSS_ESP32S3_XX` | No | Hardcoded in firmware `config.h` |
| `timestamp` | `uint32` | Unix epoch (s) | > 1700000000 | No | NTP sync (`pool.ntp.org`, UTC+7) |
| `sensors.pm25` | `float` | µg/m³ | 0.0 – 1000.0 | No | GP2Y1023AU0F PWM → formula `(W - W₀) / 14.0` |
| `sensors.co2` | `float` | ppm | 400 – 5000 | No | SCD40 I2C `readMeasurement()` (5s cycle) |
| `sensors.temperature` | `float` | °C | -10.0 – 60.0 | No | SCD40 co-read with CO2 |
| `sensors.humidity` | `float` | % | 0.0 – 100.0 | No | SCD40 co-read with CO2 |
| `computed.aqi_index` | `int` | — | 0 – 500 | No | Calculated from `pm25` via EPA breakpoints |
| `computed.aqi_level` | `string` | — | One of: `"Good"`, `"Moderate"`, `"Unhealthy for Sensitive Groups"`, `"Unhealthy"`, `"Very Unhealthy"`, `"Hazardous"` | No | Derived from `aqi_index` |
| `system.wifi_rssi` | `int` | dBm | -100 – 0 | No | `WiFi.RSSI()` |
| `system.uptime_sec` | `uint32` | seconds | 0 – 2³² | No | `millis() / 1000` |
| `system.free_heap` | `uint32` | bytes | 0 – 524288 | No | `ESP.getFreeHeap()` |

---

## 2. Layer 2 → Layer 1: MQTT Commands

| Property | Value |
| :--- | :--- |
| **Topic** | `aqss/devices/{device_id}/command` |
| **QoS** | 1 |
| **Direction** | Server → Device |
| **Trigger** | Admin action via REST `POST /api/v1/devices/{id}/command` |

### Payload Schema

```json
{
  "command": "reboot",
  "params": {}
}
```

### Supported Commands

| Command | Params | Description |
| :--- | :--- | :--- |
| `reboot` | `{}` | Triggers `ESP.restart()` on the device |
| `set_interval` | `{"interval_ms": 10000}` | Change telemetry publish interval (default 5000) |
| `recalibrate_dust` | `{"baseline_w0": 1350}` | Update GP2Y1023 baseline in EEPROM |
| `force_ntp_sync` | `{}` | Force immediate NTP resync |

---

## 3. Layer 2 → Database: PostgreSQL Schema

### 3.1 `telemetry_data` (Partitioned by month)

| Column | PG Type | Java Type | Source JSON Path | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | `Long` | Auto-generated | PK (with `recorded_at`) |
| `recorded_at` | `TIMESTAMPTZ` | `Instant` | `timestamp` (converted) | Partition key |
| `device_id` | `VARCHAR(32)` | `String` | `device_id` | FK → `devices` |
| `pm25` | `REAL` | `Float` | `sensors.pm25` | |
| `co2` | `REAL` | `Float` | `sensors.co2` | |
| `temperature` | `REAL` | `Float` | `sensors.temperature` | |
| `humidity` | `REAL` | `Float` | `sensors.humidity` | |
| `aqi_index` | `SMALLINT` | `Short` | `computed.aqi_index` | |
| `aqi_level` | `VARCHAR(30)` | `String` | `computed.aqi_level` | Truncated to 30 chars |
| `wifi_rssi` | `SMALLINT` | `Short` | `system.wifi_rssi` | Nullable |
| `uptime_sec` | `INT` | `Integer` | `system.uptime_sec` | Nullable |
| `free_heap` | `INT` | `Integer` | `system.free_heap` | Nullable |

### 3.2 `predictions`

| Column | PG Type | Description |
| :--- | :--- | :--- |
| `id` | `BIGSERIAL` | PK |
| `device_id` | `VARCHAR(32)` | FK → `devices` |
| `predicted_at` | `TIMESTAMPTZ` | When the model ran |
| `forecast_data` | `JSONB` | Array of `{target_time, pm25, conf_upper, conf_lower}` |
| `model_version` | `VARCHAR(20)` | e.g. `"v1.0"` |

### 3.3 `model_evaluations`

| Column | PG Type | Description |
| :--- | :--- | :--- |
| `id` | `BIGSERIAL` | PK |
| `device_id` | `VARCHAR(32)` | FK → `devices` |
| `evaluated_at` | `TIMESTAMPTZ` | When the evaluation ran |
| `is_sensor_anomaly` | `BOOLEAN` | True if Autoencoder flags anomaly |
| `reconstruction_error` | `REAL` | MAE between input and reconstructed output |
| `assessed_aqi` | `SMALLINT` | AQI after anomaly-aware averaging |
| `reliability_score` | `REAL` | 0–100, derived from `100 - (reconstruction_error × 10)` |

### 3.4 Materialized Views

#### `telemetry_hourly`

| Column | Type | Aggregation |
| :--- | :--- | :--- |
| `bucket` | `TIMESTAMPTZ` | `date_trunc('hour', recorded_at)` |
| `device_id` | `VARCHAR(32)` | GROUP BY |
| `avg_pm25` | `REAL` | `AVG(pm25)` |
| `min_pm25` | `REAL` | `MIN(pm25)` |
| `max_pm25` | `REAL` | `MAX(pm25)` |
| `avg_co2` | `REAL` | `AVG(co2)` |
| `min_co2` | `REAL` | `MIN(co2)` |
| `max_co2` | `REAL` | `MAX(co2)` |
| `avg_temp` | `REAL` | `AVG(temperature)` |
| `avg_hum` | `REAL` | `AVG(humidity)` |
| `avg_aqi` | `SMALLINT` | `AVG(aqi_index)` |
| `max_aqi` | `SMALLINT` | `MAX(aqi_index)` |
| `sample_count` | `BIGINT` | `COUNT(*)` |

> **Refresh interval:** Every 15 minutes via `@Scheduled` cron.

#### `telemetry_daily`

Same schema as `telemetry_hourly` but bucketed by `date_trunc('day', ...)`. Refreshed hourly at `:05`.

---

## 4. Layer 2 → Layer 4: REST API Responses

### 4.1 `GET /api/v1/telemetry/latest?device_id={id}`

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "recorded_at": "2026-08-21T10:35:00Z",
  "pm25": 35.4,
  "co2": 620.0,
  "temperature": 29.5,
  "humidity": 68.2,
  "aqi_index": 101,
  "aqi_level": "Unhealthy for Sensitive Groups",
  "wifi_rssi": -65,
  "uptime_sec": 3600,
  "free_heap": 245760
}
```

### 4.2 `GET /api/v1/telemetry/history?device_id={id}&start={ISO}&end={ISO}`

```json
[
  {
    "recorded_at": "2026-08-21T10:00:00Z",
    "pm25": 30.1,
    "co2": 610.0,
    "temperature": 28.9,
    "humidity": 70.0,
    "aqi_index": 89,
    "aqi_level": "Moderate"
  },
  {
    "recorded_at": "2026-08-21T10:00:05Z",
    "pm25": 31.2,
    "co2": 615.0,
    "temperature": 29.0,
    "humidity": 69.8,
    "aqi_index": 91,
    "aqi_level": "Moderate"
  }
]
```

### 4.3 `GET /api/v1/telemetry/aggregate?device_id={id}&interval={5min|hour|day}&start={ISO}&end={ISO}`

```json
[
  {
    "bucket": "2026-08-21T10:00:00Z",
    "device_id": "AQSS_ESP32S3_01",
    "avg_pm25": 32.5,
    "max_pm25": 45.0,
    "avg_co2": 620.0,
    "avg_temp": 29.2,
    "avg_hum": 69.5,
    "avg_aqi": 95,
    "sample_count": 720
  }
]
```

### 4.4 `GET /api/v1/forecast/{device_id}`

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "predicted_at": "2026-08-21T10:00:00Z",
  "model_version": "v1.0",
  "forecast_sequence": [
    {
      "target_time": "2026-08-21T11:00:00Z",
      "pm25": 38.2,
      "conf_upper": 45.1,
      "conf_lower": 31.3
    },
    {
      "target_time": "2026-08-21T12:00:00Z",
      "pm25": 41.0,
      "conf_upper": 50.5,
      "conf_lower": 31.5
    }
  ]
}
```

> **Note:** `forecast_sequence` contains exactly **24 objects**, one per hour for the next 24 hours.

### 4.5 `GET /api/v1/devices`

```json
[
  {
    "device_id": "AQSS_ESP32S3_01",
    "name": "Office Sensor A",
    "location_name": "Hanoi - Cau Giay",
    "latitude": 21.0285,
    "longitude": 105.8542,
    "firmware_version": "1.0.0",
    "is_active": true,
    "last_seen_at": "2026-08-21T10:34:55Z"
  }
]
```

### 4.6 `GET /api/v1/alerts`

```json
[
  {
    "id": 42,
    "device_id": "AQSS_ESP32S3_01",
    "alert_type": "AQI_THRESHOLD",
    "severity": "WARNING",
    "message": "PM2.5 exceeded 150 µg/m³ for 10 consecutive minutes",
    "triggered_at": "2026-08-21T09:15:00Z",
    "resolved_at": null
  }
]
```

### 4.7 Authentication Endpoints

#### `POST /api/v1/auth/token` — Request

```json
{
  "email": "admin@aqss.io",
  "password": "securePassword123"
}
```

#### `POST /api/v1/auth/token` — Response

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 1800
}
```

---

## 5. Layer 2 → Layer 4: STOMP WebSocket Messages

| Property | Value |
| :--- | :--- |
| **Endpoint** | `ws://localhost:8080/ws` (native) or SockJS fallback |
| **Protocol** | STOMP 1.2 over WebSocket |
| **Auth** | JWT in STOMP `CONNECT` header: `Authorization: Bearer <jwt>` |

### 5.1 `/topic/devices/{device_id}` — Live Telemetry

Pushed immediately (<5ms) when an MQTT message is ingested. Same shape as the MQTT payload:

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "timestamp": 1724217600,
  "sensors": {
    "pm25": 35.4,
    "co2": 620,
    "temperature": 29.5,
    "humidity": 68.2
  },
  "computed": {
    "aqi_index": 101,
    "aqi_level": "Unhealthy for Sensitive Groups"
  },
  "system": {
    "wifi_rssi": -65,
    "uptime_sec": 3600,
    "free_heap": 245760
  }
}
```

### 5.2 `/topic/alerts` — Alert Notifications

```json
{
  "id": 42,
  "device_id": "AQSS_ESP32S3_01",
  "alert_type": "AQI_THRESHOLD",
  "severity": "WARNING",
  "message": "PM2.5 exceeded 150 µg/m³",
  "triggered_at": "2026-08-21T09:15:00Z"
}
```

### 5.3 `/topic/device-status` — Online/Offline Events

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "status": "ONLINE",
  "last_seen_at": "2026-08-21T10:35:00Z"
}
```

---

## 6. Layer 2 → Layer 3: DL Prediction Request

| Property | Value |
| :--- | :--- |
| **Protocol** | HTTP POST |
| **URL** | `http://layer3-dl:8000/api/v1/predict` |
| **Content-Type** | `application/json` |
| **Trigger** | `@Scheduled` cron in Spring Boot — every **1 hour** |
| **Timeout** | 30 seconds |

### Request Body

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "data_since_6am": [
    {
      "timestamp": "2026-08-21T06:00:00+07:00",
      "pm25": 22.1,
      "co2": 480.0,
      "temperature": 27.0,
      "humidity": 75.0
    },
    {
      "timestamp": "2026-08-21T06:00:05+07:00",
      "pm25": 22.3,
      "co2": 482.0,
      "temperature": 27.0,
      "humidity": 74.9
    }
  ],
  "data_last_hour": [
    {
      "timestamp": "2026-08-21T09:00:00+07:00",
      "pm25": 35.0,
      "co2": 610.0,
      "temperature": 29.0,
      "humidity": 68.5
    }
  ]
}
```

### Field Validation (Pydantic on Layer 3)

| Field | Type | Constraint |
| :--- | :---: | :--- |
| `timestamp` | `string` (ISO 8601) | Must be parseable |
| `pm25` | `float` | `>= 0.0`, `<= 2000.0` |
| `co2` | `float` | `>= 400.0`, `<= 5000.0` |
| `temperature` | `float` | `>= -20.0`, `<= 80.0` |
| `humidity` | `float` | `>= 0.0`, `<= 100.0` |

> **Important:** `data_since_6am` length is variable (from 1 to ~12,960 records for a full day at 5s intervals). `data_last_hour` length is fixed at ~720 records (1 hour ÷ 5s).

---

## 7. Layer 3 → Layer 2: DL Prediction Response

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "status": "success",
  "forecast_sequence": [
    {
      "target_time": "2026-08-21T11:00:00+07:00",
      "pm25": 38.2,
      "conf_upper": 45.1,
      "conf_lower": 31.3
    },
    {
      "target_time": "2026-08-21T12:00:00+07:00",
      "pm25": 41.0,
      "conf_upper": 50.5,
      "conf_lower": 31.5
    }
  ]
}
```

| Field | Type | Description |
| :--- | :---: | :--- |
| `status` | `string` | `"success"` or `"error"` |
| `forecast_sequence` | `array[24]` | Exactly 24 objects, one per future hour |
| `forecast_sequence[].target_time` | `string` (ISO 8601) | The future timestamp being predicted |
| `forecast_sequence[].pm25` | `float` | Mean predicted PM2.5 |
| `forecast_sequence[].conf_upper` | `float` | Upper bound of 95% confidence interval |
| `forecast_sequence[].conf_lower` | `float` | Lower bound of 95% confidence interval |

> **Layer 2 Action:** Persist entire `forecast_sequence` array as `JSONB` into `predictions.forecast_data`.

---

## 8. Layer 2 → Layer 3: DL Evaluation Request

| Property | Value |
| :--- | :--- |
| **Protocol** | HTTP POST |
| **URL** | `http://layer3-dl:8000/api/v1/evaluate` |
| **Trigger** | `@Scheduled` cron — every **1 hour** (after prediction) |

### Request Body

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "target_window": [
    {
      "timestamp": "2026-08-21T09:00:00+07:00",
      "pm25": 35.0,
      "co2": 610.0,
      "temperature": 29.0,
      "humidity": 68.5
    }
  ],
  "reference_window": [
    {
      "timestamp": "2026-08-21T08:00:00+07:00",
      "pm25": 28.0,
      "co2": 590.0,
      "temperature": 28.2,
      "humidity": 70.1
    }
  ]
}
```

| Window | Content | Record Count |
| :--- | :--- | :--- |
| `target_window` | Data from **past 1 hour** (hourly eval) or **past 24 hours** (daily eval) | ~720 (hourly) or ~17,280 (daily) |
| `reference_window` | Data from the **hour/day before** the target | Same as target |

---

## 9. Layer 3 → Layer 2: DL Evaluation Response

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "status": "success",
  "evaluation": {
    "is_sensor_anomaly": false,
    "reconstruction_error": 0.042,
    "assessed_aqi": 95,
    "reliability_score": 99.58
  }
}
```

| Field | Type | Range | Description |
| :--- | :---: | :--- | :--- |
| `is_sensor_anomaly` | `boolean` | — | `true` if Autoencoder reconstruction error > threshold τ |
| `reconstruction_error` | `float` | 0.0 – ∞ | MAE between input sequence and reconstructed output |
| `assessed_aqi` | `int` | 0 – 500 | EMA-weighted AQI (only valid when `is_sensor_anomaly == false`) |
| `reliability_score` | `float` | 0.0 – 100.0 | `max(0, 100 - (reconstruction_error × 10))` |

> **Layer 2 Action:** Persist into `model_evaluations` table. If `is_sensor_anomaly == true`, also create an entry in the `alerts` table with `alert_type = "SENSOR_ANOMALY"`.

---

## 10. Layer 4 → Layer 2: REST API Requests

### 10.1 Authentication Headers

All protected endpoints require:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

Device-to-server endpoints (MQTT ingestion proxy, if used) require:

```
X-API-Key: <raw-api-key>
```

### 10.2 Request Summary Table

| Endpoint | Method | Auth | Request Body | Response |
| :--- | :---: | :---: | :--- | :--- |
| `/api/v1/auth/register` | POST | None | `{email, password, full_name}` | `{id, email, role}` |
| `/api/v1/auth/token` | POST | None | `{email, password}` | `{access_token, refresh_token, ...}` |
| `/api/v1/auth/refresh` | POST | Refresh | `{refresh_token}` | `{access_token, ...}` |
| `/api/v1/devices` | GET | JWT | — | `[Device]` |
| `/api/v1/devices/{id}` | GET | JWT | — | `Device` |
| `/api/v1/devices` | POST | ADMIN | `{device_id, name, location_name, lat, lng}` | `{device_id, api_key}` |
| `/api/v1/devices/{id}/command` | POST | ADMIN | `{command, params}` | `{status: "sent"}` |
| `/api/v1/telemetry/latest` | GET | JWT | `?device_id=` | `TelemetryData` |
| `/api/v1/telemetry/history` | GET | JWT | `?device_id=&start=&end=` | `[TelemetryData]` |
| `/api/v1/telemetry/aggregate` | GET | JWT | `?device_id=&interval=&start=&end=` | `[AggregateRow]` |
| `/api/v1/telemetry/hourly` | GET | JWT | `?device_id=&start=&end=` | `[HourlyRow]` |
| `/api/v1/telemetry/daily` | GET | JWT | `?device_id=&start=&end=` | `[DailyRow]` |
| `/api/v1/forecast/{device_id}` | GET | JWT | — | `ForecastResult` |
| `/api/v1/forecast/run` | POST | ADMIN | `{device_id}` | `{status: "triggered"}` |
| `/api/v1/alerts` | GET | JWT | — | `[Alert]` |
| `/api/v1/alerts/{id}/resolve` | PUT | JWT | — | `{status: "resolved"}` |

---

## 11. Sensor Value Ranges & Validation Rules

This table defines the physical boundaries for all sensor data. Values outside these ranges should be **rejected** (Layer 3 Pydantic) or **flagged** (Layer 2 AlertService).

| Sensor | Min | Max | Unit | Reject If | Alert If |
| :--- | :---: | :---: | :---: | :--- | :--- |
| PM2.5 | 0.0 | 1000.0 | µg/m³ | `< 0` or `> 2000` | `> 150` for 10+ minutes |
| CO2 | 400 | 5000 | ppm | `< 300` or `> 10000` | `> 2000` |
| Temperature | -10.0 | 60.0 | °C | `< -40` or `> 85` | `> 50` (likely sensor error) |
| Humidity | 0.0 | 100.0 | % | `< 0` or `> 100` | — |
| AQI Index | 0 | 500 | — | `< 0` or `> 500` | `> 150` |
| Wi-Fi RSSI | -100 | 0 | dBm | `> 0` | `< -80` (weak signal) |

---

## 12. AQI Breakpoint Table (EPA Standard)

Used by Layer 1 (firmware `aqi_calculator.cpp`) and Layer 3 (evaluation model).

| AQI Range | Category | PM2.5 (µg/m³, 24h avg) | Color Hex | Text Color |
| :---: | :--- | :---: | :---: | :---: |
| 0 – 50 | Good | 0.0 – 12.0 | `#00E400` | Dark |
| 51 – 100 | Moderate | 12.1 – 35.4 | `#FFFF00` | Dark |
| 101 – 150 | Unhealthy for Sensitive Groups | 35.5 – 55.4 | `#FF7E00` | Dark |
| 151 – 200 | Unhealthy | 55.5 – 150.4 | `#FF0000` | White |
| 201 – 300 | Very Unhealthy | 150.5 – 250.4 | `#8F3F97` | White |
| 301 – 500 | Hazardous | 250.5 – 500.4 | `#7E0023` | White |

### AQI Calculation Formula (Linear Interpolation)

$$
AQI = \frac{I_{Hi} - I_{Lo}}{BP_{Hi} - BP_{Lo}} \times (C_p - BP_{Lo}) + I_{Lo}
$$

Where:
- $C_p$ = PM2.5 concentration
- $BP_{Hi}$, $BP_{Lo}$ = Breakpoint concentration bounds
- $I_{Hi}$, $I_{Lo}$ = Corresponding AQI bounds

---

## Data Flow Diagram (End-to-End)

```mermaid
graph LR
    subgraph "Layer 1 (ESP32-S3)"
        S1[SCD40<br>CO2/Temp/Hum]
        S2[GP2Y1023<br>PM2.5 PWM]
        MCU[ESP32-S3<br>FreeRTOS]
        S1 -->|I2C 400kHz| MCU
        S2 -->|GPIO 18 ISR| MCU
    end

    subgraph "Layer 2 (Spring Boot)"
        MQTT[Mosquitto<br>MQTT Broker]
        SB[Spring Boot 3<br>Java 21]
        DB[(PostgreSQL 16<br>Partitioned)]
        SB -->|Flyway| DB
        SB -->|STOMP| WS[WebSocket<br>Endpoint]
    end

    subgraph "Layer 3 (PyTorch)"
        FA[FastAPI<br>:8000]
        M1[Predictor<br>Seq2Seq LSTM]
        M2[Evaluator<br>Autoencoder]
        FA --> M1
        FA --> M2
    end

    subgraph "Layer 4 (React)"
        FE[React 18<br>TypeScript]
        RC[Recharts<br>Visualization]
        FE --> RC
    end

    MCU -->|MQTT QoS1<br>5s interval| MQTT
    MQTT -->|Spring Integration| SB
    SB -->|HTTP POST<br>hourly| FA
    FA -->|JSON Response| SB
    SB -->|REST API| FE
    WS -->|STOMP frames<br>real-time| FE
```
