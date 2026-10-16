# Air Quality Supervision System (AQSS) — Closed-Loop Indoor Control

You are working on the **Air Quality Supervision System**, an end-to-end IoT project that measures indoor air quality, forecasts it, and **acts on it** — a closed-loop control system, not a passive monitor. The system is built across four distinct layers.

**Core idea:** air-quality thresholds are not fixed numbers — they are functions of the space (volume · purpose · region). The backend infers a per-space threshold, computes the intervention dosage, and actuates *before* the threshold is breached.

## Architecture

| Layer | Directory | Stack | Description |
| :---: | :--- | :--- | :--- |
| 1 | `embedded/` | ESP32-S3, C++17, FreeRTOS, PlatformIO, Arduino Core | **Single integrated node**: sensor acquisition, OLED early-warning display, Intel 4-wire PWM fan control, MQTT telemetry |
| 2 | `backend/` | Java 21, Spring Boot 3.3, Maven, PostgreSQL 16, Mosquitto, STOMP WebSocket | **The brain**: Space management, control decisions, actuation, MQTT ingestion, REST API, JWT auth, RBAC |
| 3 | `layer3_dl/` | Python 3.11, PyTorch 2.2, FastAPI, Scikit-Learn, XGBoost | ML#1 Threshold (GPR), ML#2 Dosage (XGBoost), Seq2Seq LSTM forecasting (24h, quantile) |
| 4 | `frontend/` | React 18, TypeScript, Vite, TailwindCSS, Zustand, TanStack Query, Recharts | Space-centric dashboard, threshold band, forecast quantiles, actuator control, decision timeline |

## Hardware

- **MCU:** ESP32-S3-DevKitC-1 (WROOM-1 N16R8) — Dual-core 240 MHz, 16 MB Flash, 8 MB PSRAM.
- **CO2 Sensor:** Sensirion SCD40 — I2C `0x62`, periodic mode every 5 s. **SCD40 has no single-shot mode.**
- **PM2.5 Sensor:** Sharp GP2Y1023AU0F — **digital PWM output** (NOT analog), 100 Hz, sensitivity 14 µs/(µg/m³), range 0–500 µg/m³ (saturates ~750). Voltage divider 1 kΩ / 2 kΩ required.
- **Display:** SSD1306 OLED 128×64 0.96″ — I2C `0x3C`, shared bus with SCD40 locked at **400 kHz**.
- **Actuator:** Intel CPU cooler fan, **4-wire PWM** — pin 2 on a constant +12 V (fused) rail, speed set by a 25 kHz PWM signal on pin 4. **Never PWM the power line.** Fan levels map to duty: 0→0 %, 1→35 %, 2→60 %, 3→90 %; `min_duty` ≈ 20 % and a kickstart pulse prevent BLDC stall.
- **Filtration:** HEPA H13 media on the fan outlet — the duct must be **airtight** (leaks bypass the filter).
- **Power:** 12 V PSU → fuse → fan; LM2596 buck (verify 5.0 V with a meter BEFORE connecting the ESP32) → ESP32 VIN.

## Critical Hardware Rules

1. **GP2Y1023AU0F is NOT GP2Y1010AU0F.** Built-in MCU, self-triggered LED. Read with an `IRAM_ATTR` ISR + `micros()`. Never `analogRead()`, never pulse an LED pin — there is none.
2. **GPIO 18** = dust sensor PWM input (via the voltage divider). Valid pulses are 6.8 µs – 7000 µs.
3. **GPIO 8 (SDA) / GPIO 9 (SCL)** = shared I2C bus (SCD40 + OLED), locked at **400 kHz**. Call `Wire.begin()` **once** — the U8g2 constructor must not re-initialise the same peripheral.
4. **GPIO 33–37** are reserved by Octal PSRAM on N16R8. Never use them.
5. **ADC2 (GPIO 11–20)** is unavailable while Wi-Fi is active. GPIO 18 is a digital input, not ADC.
6. The ESP32-S3 has **no EEPROM** — persist `space_id`, fan baseline W₀ and config in **NVS**.
7. Common GND between PSU, fan and ESP32 is mandatory.

## Sensor Validation Rules (one table, every layer)

| Sensor | Unit | Valid Range | Reject If | Alert If |
| :--- | :---: | :--- | :--- | :--- |
| PM2.5 | µg/m³ | 0.0 – 500.0 | `< 0` or `> 750` (saturation) | Space-derived threshold |
| CO2 | ppm | 400 – 5000 | `< 300` or `> 10000` | `> 1000` — **alert only** |
| Temperature | °C | −10.0 – 60.0 | `< −40` or `> 85` | `> 50` |
| Humidity | % | 0.0 – 100.0 | `< 0` or `> 100` | — |
| Wi-Fi RSSI | dBm | −100 – 0 | `> 0` | `< −80` |

> AQI is **not** in this table: it is a derived, display-only metric (EPA NowCast computed by the backend) and is never used by the control loop.

## MQTT Contract (full registry in `docs/io_contract.md` §8)

| Topic | Direction | Notes |
| :--- | :--- | :--- |
| `aqss/devices/{id}/telemetry` | Device → Server | QoS 1, 5 s. JSON: `device_id`, `space_id`, `timestamp`, `sensors{pm25,co2,temperature,humidity}`, `system{wifi_rssi,uptime_sec,free_heap,min_free_heap}`. **No AQI on the device.** Buffer ≥ 512 B. |
| `aqss/devices/{id}/status` | Device → Server | Retained. `ONLINE` on connect, `OFFLINE` via **LWT**. |
| `aqss/devices/{id}/actuator/state` | Device → Server | `{state, level, duty_pct, mode, filter_runtime_h}`. |
| `aqss/devices/{id}/command` | Server → Device | `set_actuator{state,level}` · `display_warning{message,severity}` · `set_space{space_id}` · `reboot` · `set_interval` · `recalibrate_dust` (→ NVS) · `force_ntp_sync`. |

Firmware must **null-check** `doc["command"]` before `strcmp` — malformed payloads must not crash the node.

## Decision Rules (backend `ControlDecisionService`, 60 s cycle)

1. `pm25 ≥ space_threshold` → ML#2 dosage → publish `set_actuator ON` → audit `ACTIVATE/AUTO/threshold_breach`.
2. `q90 forecast breach within 2 h` → push `display_warning` to the OLED → audit `ALERT`.
3. `pm25 < 0.7 × threshold` while the fan is ON → `set_actuator OFF` → audit `DEACTIVATE/hysteresis_off`.
4. Safety: min ON 5 min · min OFF 3 min · max ON 120 min · manual override always wins · node fail-safe OFF after 10 min without a command.
5. **CO2 never actuates.** `CO2_ALERT` is advisory only (HEPA cannot remove CO2).

## REST API (Layer 2 ↔ 4)

All protected endpoints require `Authorization: Bearer <JWT>`; device endpoints use `X-API-Key`.

| Endpoint | Method | Purpose |
| :--- | :---: | :--- |
| `/api/v1/auth/token` | POST | Login → JWT |
| `/api/v1/spaces` | GET/POST | List / create spaces |
| `/api/v1/spaces/{id}` | GET/PUT/DELETE | Space + threshold + dosage + devices |
| `/api/v1/spaces/{id}/decisions` | GET | Decision timeline (audit) |
| `/api/v1/spaces/{id}/actuator` | POST | Manual AUTO/MANUAL control |
| `/api/v1/spaces/{id}/threshold` | POST | Force ML#1 recompute |
| `/api/v1/actuators` | GET | Actuator registry + live state |
| `/api/v1/telemetry/latest` | GET | Latest reading + derived AQI + `co2_alert` |
| `/api/v1/telemetry/history` / `aggregate` | GET | Range / `date_trunc` queries |
| `/api/v1/forecast/{space_id}` | GET | 96-point quantile forecast |
| `/api/v1/alerts` | GET/PUT | Alerts / resolve |

## WebSocket Contract (Layer 2 → 4)

STOMP 1.2 at `/ws` (SockJS fallback), JWT in the `CONNECT` header:

`/topic/devices/{id}` · `/topic/spaces/{id}/decision` · `/topic/actuators/{id}` · `/topic/alerts` · `/topic/device-status`

## AI API Contract (Layer 2 → 3)

| Endpoint | Purpose |
| :--- | :--- |
| `POST /api/v1/threshold` | ML#1 — space profile → `{base_threshold_pm25, uncertainty_lower, uncertainty_upper}` |
| `POST /api/v1/dosage` | ML#2 — space + current PM2.5 + threshold → `{filter_level, run_duration_minutes}` |
| `POST /api/v1/forecast` | DL — **96 points @ 15-min median resample (24 h)** → 96 × `{pm25_q10, pm25_q50, pm25_q90}` |

Pydantic bounds match the sensor table above: pm25 `[0, 750]`, co2 `[400, 5000]`, temperature `[−10, 60]`, humidity `[0, 100]`.
`/api/v1/evaluate` (Autoencoder) is **removed** in V2.

## Subagents

Defined in `.claude/agents/` (file names are authoritative, typos included):

| Agent | Layer | Purpose |
| :--- | :---: | :--- |
| `embedded-engineer` | 1 | Wiring, firmware architecture, FreeRTOS task design |
| `cpp-platformIO-pro` | 1 | C++17, PlatformIO builds, LEDC PWM, library integration |
| `backend-developer` | 2 | Spring Boot API, MQTT ingestion, control decision service |
| `java-springboot-pro` | 2 | Java 21 conventions, Maven, JPA, Spring Security |
| `prosgresql-pro` | 2 | Schema, partitioning, matviews, query optimisation |
| `websocket-pro` | 2 | STOMP config, MQTT→STOMP bridging |
| `dl-engineer` | 3 | Model architecture, physics-informed labelling, training pipelines |
| `py-pytourch-pro` | 3 | PyTorch quality, quantile loss, tensor operations |
| `frontend-developer` | 4 | React components, data integration, control views |
| `react-typescripts-pro` | 4 | TypeScript strict, hooks, TanStack Query, Zustand |
| `ui-designer` | 4 | Design system, AQI palette, dark mode, accessibility |
| `qa-expert` | All | Testing strategy, integration tests, load tests |

## Key Documentation

| Document | Path | Purpose |
| :--- | :--- | :--- |
| **Concept & Architecture** | `docs/aqss-concept-v2.md` | Thesis, control loop, models, hardware, roadmap |
| **I/O Contract** | **`docs/io_contract.md`** | **Single source of truth for all data contracts** |
| Layer 1 Plan | `docs/embedded-layer.md` | Integrated-node firmware |
| Layer 2 Plan | `docs/backend-layer.md` | Brain: ingestion, decisions, actuation |
| Layer 3 Plan | `docs/dl.md` | ML#1 / ML#2 / DL forecast |
| Layer 4 Plan | `docs/fontend-layer.md` | Space-centric dashboard |
| Hardware Reference | `docs/hardware_datasheet_reference.md` | Consolidated datasheet specs |
| UI Mockups | `docs/ui_mockups/` | Dashboard + Space Control designs |

> Any cross-layer schema change must update `docs/io_contract.md` first.

## Conventions

- FreeRTOS tasks pinned to cores: Wi-Fi/MQTT on **Core 0**, sensors/OLED/fan on **Core 1**; shared state behind a mutex.
- Telemetry JSON every 5 s to `aqss/devices/{id}/telemetry`; buffer ≥ 512 B.
- Backend: **Maven**, **Java 21** (not Kotlin), Spring Boot 3.3, `ddl-auto: validate`, Flyway migrations (V1–V4).
- PostgreSQL 16 native monthly range partitioning + materialized views. Partition creation **is scheduled** (`PartitionManagerService`); retention drops whole expired months. Not TimescaleDB.
- Batch writes via `LinkedBlockingQueue` flushed every 1 s with `reWriteBatchedInserts=true`.
- AI inference on **CPU-only PyTorch** (torch pinned to the CPU index) via FastAPI on port 8000.
- Frontend: **Vite** (not Next.js), React Router v6, TailwindCSS (`tailwind.config.ts`, ESM).
- All cross-layer contracts live in `docs/io_contract.md`.
