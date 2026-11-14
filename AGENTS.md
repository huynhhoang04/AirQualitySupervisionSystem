# AQSS — Project Instructions

**Air Quality Supervision System** — a closed-loop indoor air quality controller. One ESP32-S3 node
per room measures the air; a Spring Boot backend decides when to run the filter fan and why; a React
dashboard shows the loop. The threshold that triggers filtration is **derived from the room itself**,
not typed in once and forgotten.

## Architecture

| Layer | Directory | Stack | Role |
| :---: | :--- | :--- | :--- |
| 1 | `embedded/` | ESP32-S3, C++17, FreeRTOS, PlatformIO | Sensor acquisition, OLED, fan PWM, MQTT. Measures and actuates; decides nothing |
| 2 | `backend/` | Java 21, Spring Boot 3.3, Maven, PostgreSQL (Neon), Paho MQTT | **The brain**: ingestion, room thresholds, control loop, REST, AI metadata + forecast ledger |
| 3 | `frontend/` | React 19, TypeScript, Vite, TailwindCSS, TanStack Query | Desktop dashboard: overview, room, AI centre, evaluation, devices, audit, connect |
| 4 | `ai-service/` | Python 3.11, FastAPI, scikit-learn, pandas, httpx | **Optional local worker**: claims jobs, trains `feature-v1`, uploads forecasts. Never on the control path |

There is **no hosted AI dependency** and no LLM. The optional `ai-service/` worker runs on a laptop,
connects **outbound** to the backend's `/internal/v1/ai/**` with a bearer token, and stores artifacts
locally. Stopping it must not affect measurement, pairing, the threshold or the fan loop. The
threshold remains a formula (`docs/io_contract.md` §7.2); PM2.5 forecasting is the learned part.
`forecast-demo/` is kept as a reference implementation only.

## Hardware

- **MCU:** ESP32-S3-DevKitC-1 (WROOM-1 N16R8) — dual-core 240 MHz, 16 MB flash, 8 MB PSRAM.
- **CO₂ / T / RH:** Sensirion SCD40 — I2C `0x62`, periodic mode every 5 s. No single-shot mode.
- **PM2.5:** Sharp GP2Y1023AU0F — **digital PWM**, 100 Hz, 14 µs/(µg/m³), range 0–500 (saturates ~750).
  Voltage divider 1 kΩ / 2 kΩ required.
- **Display:** SSD1306 128×64 OLED — I2C `0x3C`, shared bus with the SCD40 at **400 kHz**.
- **Fan:** Intel 4-wire PWM — pin 2 on constant +12 V (fused), speed on pin 4 at 25 kHz.
  **Never PWM the power rail.** Levels: 0→0 %, 1→35 %, 2→60 %, 3→90 %; `min_duty` ≈ 20 % plus a
  kickstart pulse.
- **Filtration:** HEPA H13 media on the fan outlet; the duct must be airtight.

## Critical hardware rules

1. **GP2Y1023 is not GP2Y1010.** It has its own MCU and a self-triggered LED. Read it with an
   `IRAM_ATTR` ISR and `micros()`. Never `analogRead()`, never pulse an LED pin — there is none.
2. **GPIO 18** = dust PWM input. Valid pulse widths are 6.8 µs – 7000 µs.
3. **GPIO 8 (SDA) / GPIO 9 (SCL)** = shared I2C, 400 kHz. Call `Wire.begin()` **once**; the U8g2
   constructor must not re-initialise the same peripheral.
4. **GPIO 33–37** are reserved by Octal PSRAM on N16R8. Never use them.
5. The ESP32-S3 has **no EEPROM** — persist config in **NVS**.

## Sensor validation (one table, every layer)

| Sensor | Unit | Valid | Reject |
| :--- | :---: | :--- | :--- |
| PM2.5 | µg/m³ | 0.0 – 500.0 | outside |
| CO2 | ppm | 400 – 5000 | outside |
| Temperature | °C | −10 – 60 | outside |
| Humidity | % | 0 – 100 | outside |
| RSSI | dBm | −100 – 0 | — |

A `null` reading is "no sample yet" and is stored as `NULL`; it is not a rejection.

## The control loop

Telemetry evaluates **immediately** on arrival; a 60 s timer is the safety net. Activation never
waits — a breach means the air is already bad. Motor protection lives on the OFF side of the cycle.

```
OFF and pm25 >= threshold                → ACTIVATE level 3        (reason: threshold_breach)
ON for >= 120 min                        → DEACTIVATE              (reason: max_on)
ON, pm25 < 0.7 × threshold, ON >= 5 min  → DEACTIVATE              (reason: hysteresis_off)
otherwise (AUTO, running)                → republish every 5 min   (lease renewal)
```

**CO₂ never actuates.** HEPA cannot remove CO₂; it is shown and used as an input to the threshold.

## The threshold

Computed in-process by `ThresholdService`, from the room profile plus live CO₂:

```
15 × √(24/occupancy_hours) × ∛(V/20) × f(ventilation) × f(CO₂)   clamped to [10, 60]
```

`GET /spaces/{code}/threshold/preview` returns every factor so the dashboard can explain the number.
`POST` applies it. The operator can still type a threshold by hand; `threshold_source` records which.

## MQTT contract (full registry: `docs/io_contract.md` §1–§5)

| Topic | Direction | Notes |
| :--- | :--- | :--- |
| `aqss/devices/{id}/telemetry` | device → server | QoS 1, 5 s. JSON `device_id`, `space_id`, `pairing_code`, `timestamp`, `sensors{}`, `system{}`. Buffer ≥ 512 B |
| `aqss/devices/{id}/status` | device → server | Retained. `ONLINE` on connect, `OFFLINE` via LWT |
| `aqss/devices/{id}/actuator/state` | device → server | `{state, level, duty_pct, mode, filter_runtime_h}` |
| `aqss/devices/{id}/command` | server → device | `set_actuator` · `set_space` (empty = forget) · `reset_filter` · `set_interval` · `recalibrate_dust` · `display_warning` · `force_ntp_sync` · `reboot` |

The firmware null-checks `command` before `strcmp`, so a malformed payload cannot crash the node.

## REST API (Layer 2 ↔ 3)

No authentication. JSON is snake_case. Full table: `docs/io_contract.md` §7.

| Endpoint | Method | Purpose |
| :--- | :---: | :--- |
| `/api/v1/devices` | GET | Paired devices (`?include_unpaired=true` for all) |
| `/api/v1/devices/{id}` | GET/PUT/DELETE | Read / configure / forget |
| `/api/v1/devices/pair` | POST | Link a node by pairing code, creating the room if named |
| `/api/v1/devices/{id}/unpair` | POST | Drop the binding **and** tell the node to forget it |
| `/api/v1/devices/{id}/filter` | POST | "New filter fitted" |
| `/api/v1/devices/{id}/command` | POST | Any firmware command |
| `/api/v1/devices/{id}/latest` | GET | Newest reading |
| `/api/v1/devices/{id}/telemetry` | GET | Legacy chart data (`?from&to`, default 24 h, stride-thinned) |
| `/api/v1/devices/{id}/series` | GET | SQL buckets: median/min/max/coverage (`?bucket=1m\|5m\|15m`) |
| `/api/v1/devices/{id}/summary` | GET | avg/median/max, coverage, minutes above threshold |
| `/api/v1/devices/{id}/samples` | GET | Raw rows, cursor-paginated |
| `/api/v1/devices/{id}/commands` | GET | Command lifecycle (`REQUESTED`→`PUBLISHED`→`REPORT_MATCHED`) |
| `/api/v1/devices/{id}/actuator-history` | GET | Reported state, append-only |
| `/api/v1/devices/{id}/forecast` | GET | Latest forecast run + points |
| `/api/v1/devices/{id}/alerts` | GET | Alert episodes |
| `/api/v1/ai/status`, `/ai/jobs`, `/ai/models` | GET/POST | Worker status, train jobs, model registry |
| `/api/v1/devices/{id}/events`, `/api/v1/experiments` | GET/POST | Operator context |
| `/internal/v1/ai/**` | — | Worker-only: snapshot export, jobs, models, forecast upload (bearer token) |
| `/api/v1/spaces` | GET | Rooms + thresholds |
| `/api/v1/spaces/{code}` | GET/PUT/DELETE | Room CRUD (PUT patches) |
| `/api/v1/spaces/{code}/threshold/preview` | GET | The computed threshold, not saved |
| `/api/v1/spaces/{code}/threshold` | POST | Compute and store |
| `/api/v1/audit` | GET | Audit trail (`?device_id&limit`) |
| `/actuator/health` | GET | Liveness |

## Conventions

- FreeRTOS tasks pinned to cores: Wi-Fi/MQTT on **Core 0**, sensors/OLED/fan on **Core 1**.
- Backend: **Maven**, **Java 21**, Spring Boot 3.3, `ddl-auto: validate`, Flyway `V1`–`V6`.
- One MQTT connection (Paho) in `MqttLink`; inbound payloads become Spring events.
- Frontend: **Vite** (not Next.js), React Router v7, TailwindCSS, snake_case everywhere on the wire.
- AI worker: one training process at a time, temporal split with a 30-minute purge, promotion gate
  (mean MAE 15/30 must improve ≥ 10 %; MAE 5 may not regress > 5 %). A model that loses is still
  stored and shown.
- Any cross-layer change updates `docs/io_contract.md` first.

## Documentation

| Document | Purpose |
| :--- | :--- |
| `docs/io_contract.md` | **Single source of truth for every payload, table and endpoint** |
| `ai-service/README.md` | Local AI worker: features, split, promotion, configuration |
| `docs/upgrade-plan/` | The 7-day upgrade plan this version implements |
| `docs/aqss-concept-v2.md` | Thesis, architecture, hardware |
| `docs/embedded-layer.md` | Firmware plan |
| `docs/backend-layer.md` | Backend plan |
| `docs/fontend-layer.md` | Dashboard plan |
| `docs/deploy.md` | Render + Neon + EMQX runbook |
| `docs/hardware_datasheet_reference.md` | Datasheet specs |
