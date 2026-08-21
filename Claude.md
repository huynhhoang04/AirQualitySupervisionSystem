# Air Quality Supervision System (AQSS)

You are working on the **Air Quality Supervision System**, an end-to-end IoT project that monitors, evaluates, and predicts air quality in real time. The system is built across four distinct layers.

## Architecture

| Layer | Directory | Stack | Description |
| :---: | :--- | :--- | :--- |
| 1 | `embedded/` | ESP32-S3, C++17, FreeRTOS, PlatformIO, Arduino Core | Sensor acquisition (SCD40 + GP2Y1023AU0F), OLED display, MQTT telemetry |
| 2 | `backend/` | Java 21, Spring Boot 3.3, Maven, PostgreSQL 16, Mosquitto, STOMP WebSocket | MQTT ingestion, REST API, JWT auth, RBAC, batch writes, materialized views |
| 3 | `layer3_dl/` | Python 3.11, PyTorch 2.2, FastAPI, Pandas, Scikit-Learn | Seq2Seq LSTM forecasting (24h), Autoencoder anomaly evaluation |
| 4 | `frontend/` | React 18, TypeScript, Vite, TailwindCSS, Zustand, TanStack Query, Recharts | Real-time dark-mode dashboard, AQI gauge, forecast charts with confidence intervals |

## Hardware

- **MCU:** ESP32-S3-DevKitC-1 (WROOM-1 N16R8) — Dual-core 240 MHz, 16 MB Flash, 8 MB PSRAM, Wi-Fi 802.11n, BLE 5.0.
- **CO2 Sensor:** Sensirion SCD40 — I2C address `0x62`, periodic mode every 5 s, accuracy ±(50 ppm + 5%).
- **PM2.5 Sensor:** Sharp GP2Y1023AU0F — **Digital PWM output** (NOT analog), 100 Hz, sensitivity 14 µs/(µg/m³). Requires voltage divider (1 kΩ / 2 kΩ) for 5 V → 3.3 V level shifting.
- **Display:** SSD1306 OLED 128×64 0.96″ — I2C address `0x3C`, shared bus with SCD40 at 400 kHz.

## Critical Hardware Rules

1. **GP2Y1023AU0F is NOT GP2Y1010AU0F.** It has a built-in MCU. Read it with `IRAM_ATTR` ISR + `micros()`. Never use `analogRead()`. Never pulse an LED pin — there is none.
2. **GPIO 18** is designated for the dust sensor PWM input (through voltage divider).
3. **GPIO 8 (SDA)** and **GPIO 9 (SCL)** are shared I2C bus for SCD40 + OLED. Lock bus speed to **400 kHz**.
4. **GPIO 33–37** are reserved by Octal PSRAM on N16R8. Never use them.
5. **ADC2 (GPIO 11–20)** is unavailable when Wi-Fi is active. GPIO 18 is used as digital input, NOT ADC.

## Sensor Value Validation Rules

All layers must enforce these physical boundaries. Values outside "Reject" range must be dropped; values exceeding "Alert" thresholds trigger notifications.

| Sensor | Unit | Valid Range | Reject If | Alert If |
| :--- | :---: | :--- | :--- | :--- |
| PM2.5 | µg/m³ | 0.0 – 1000.0 | `< 0` or `> 2000` | `> 150` for 10+ min |
| CO2 | ppm | 400 – 5000 | `< 300` or `> 10000` | `> 2000` |
| Temperature | °C | -10.0 – 60.0 | `< -40` or `> 85` | `> 50` |
| Humidity | % | 0.0 – 100.0 | `< 0` or `> 100` | — |
| AQI Index | — | 0 – 500 | `< 0` or `> 500` | `> 150` |
| Wi-Fi RSSI | dBm | -100 – 0 | `> 0` | `< -80` |

## MQTT Contract (Layer 1 → Layer 2)

- **Topic:** `aqss/devices/{device_id}/telemetry` (QoS 1, every 5 seconds)
- **Payload:** JSON with `device_id`, `timestamp` (Unix epoch), `sensors` (pm25, co2, temperature, humidity), `computed` (aqi_index, aqi_level), `system` (wifi_rssi, uptime_sec, free_heap)
- **Command topic:** `aqss/devices/{device_id}/command` (Server → Device)

## REST API Contract (Layer 2 → Layer 4)

All protected endpoints require `Authorization: Bearer <JWT>`. Device endpoints use `X-API-Key` header.

| Endpoint | Method | Auth | Purpose |
| :--- | :---: | :---: | :--- |
| `/api/v1/auth/token` | POST | None | Login → JWT |
| `/api/v1/devices` | GET | JWT | List all devices |
| `/api/v1/telemetry/latest` | GET | JWT | Latest reading per device |
| `/api/v1/telemetry/history` | GET | JWT | Raw range query |
| `/api/v1/telemetry/aggregate` | GET | JWT | `date_trunc()` aggregates |
| `/api/v1/forecast/{device_id}` | GET | JWT | 24-point forecast sequence |
| `/api/v1/alerts` | GET | JWT | Active alerts list |

## WebSocket Contract (Layer 2 → Layer 4)

- **Protocol:** STOMP 1.2 over WebSocket at `/ws` (with SockJS fallback)
- **Auth:** JWT in STOMP `CONNECT` header
- **Topics:** `/topic/devices/{id}` (live telemetry), `/topic/alerts`, `/topic/device-status`

## DL API Contract (Layer 2 → Layer 3)

- **Prediction:** `POST http://layer3-dl:8000/api/v1/predict` — receives sensor arrays (6AM-to-now + last 1h), returns 24-point `forecast_sequence` with `conf_upper` / `conf_lower`
- **Evaluation:** `POST http://layer3-dl:8000/api/v1/evaluate` — receives target/reference windows, returns `is_sensor_anomaly`, `reconstruction_error`, `assessed_aqi`, `reliability_score`
- **Pydantic validation:** pm25 [0, 2000], co2 [400, 5000], temperature [-20, 80], humidity [0, 100]

## Subagents

Specialized agents are defined in `.claude/agents/`:

| Agent | Layer | Purpose |
| :--- | :---: | :--- |
| `embedded-engineer` | 1 | Hardware wiring, firmware architecture, FreeRTOS task design |
| `cpp-platformIO-pro` | 1 | C++17 code, PlatformIO builds, library integration |
| `backend-developer` | 2 | Spring Boot API, MQTT ingestion, service layer |
| `java-springboot-pro` | 2 | Java 21 conventions, Maven, JPA, Spring Security |
| `prosgresql-pro` | 2 | Schema design, partitioning, query optimization |
| `websocket-pro` | 2 | STOMP WebSocket config, MQTT-to-STOMP bridging |
| `dl-engineer` | 3 | PyTorch model architecture, training pipelines, MLOps |
| `py-pytourch-pro` | 3 | PyTorch code quality, tensor operations, model export |
| `frontend-developer` | 4 | React components, data integration, dashboard features |
| `react-typescripts-pro` | 4 | TypeScript strict mode, hooks, TanStack Query, Zustand |
| `ui-designer` | 4 | Design system, AQI color palette, dark mode, accessibility |
| `qa-expert` | All | Testing strategy, integration tests, load tests |

## Key Documentation

| Document | Path | Purpose |
| :--- | :--- | :--- |
| Hardware Reference | `docs/hardware_datasheet_reference.md` | Consolidated datasheet specs |
| Layer 1 Plan | `docs/plan_layer1_embedded.md` | Embedded firmware implementation |
| Layer 2 Plan | `docs/plan_layer2_backend.md` | Spring Boot backend implementation |
| Layer 3 Plan | `docs/plan_layer3_dl.md` | Deep learning model design |
| Layer 4 Plan | `docs/plan_layer4_frontend.md` | Frontend dashboard implementation |
| **I/O Contract** | **`docs/io_contract.md`** | **Single source of truth for all data contracts between layers** |
| UI Mockups | `docs/ui_mockups/` | Generated dashboard and forecasting screen designs |

## Conventions

- All embedded code uses **FreeRTOS tasks** pinned to specific cores (Wi-Fi/MQTT on Core 0, Sensors/OLED on Core 1).
- Shared sensor data is protected by a **FreeRTOS mutex** (`SemaphoreHandle_t`).
- MQTT telemetry payload is **JSON**, published every 5 seconds to `aqss/devices/{id}/telemetry`.
- Backend uses **Maven** (`pom.xml`), **Java 21** (NOT Kotlin), Spring Boot 3.3 with `ddl-auto: validate` (Flyway manages schema).
- Database uses **PostgreSQL 16** native range partitioning (monthly) with materialized views. NOT TimescaleDB.
- Backend batch writes use `LinkedBlockingQueue` flushed every 1s with `reWriteBatchedInserts=true`.
- AI inference runs on **CPU-only PyTorch** via FastAPI on port 8000 as a separate Docker container.
- Frontend uses **Vite** (NOT Next.js), React Router v6, and TailwindCSS for styling.
- All cross-layer data contracts are documented in `docs/io_contract.md`. Any schema change must update that file.
