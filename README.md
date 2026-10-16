# Air Quality Supervision System (AQSS) — Closed-Loop Indoor Control

An end-to-end IoT project that measures indoor air quality, forecasts it, and **acts on it**. Space-aware thresholds, dosage prescriptions and anticipatory actuation (a PWM-controlled filter fan) driven by Deep Learning forecasts — not a passive monitor.

> **Core idea:** air-quality thresholds are not fixed numbers — they are functions of the space. The system infers a per-room threshold, computes the intervention dosage, and intervenes *before* the threshold is breached.

## Architecture Overview

| Layer | Component | Tech Stack | Description |
| :---: | :--- | :--- | :--- |
| **1** | Embedded IoT | ESP32-S3, C++17, FreeRTOS | **Single integrated node**: SCD40 + GP2Y1023 sensing, OLED early-warning display, Intel 4-wire PWM fan control, MQTT telemetry. |
| **2** | Backend Services | Java 21, Spring Boot 3.3, PostgreSQL 16 | **The brain**: Space management, control decisions, actuation, MQTT ingestion, REST API, JWT auth, RBAC, batch writes, STOMP WebSocket. |
| **3** | AI | Python 3.11, PyTorch 2.2, FastAPI | ML#1 Threshold (GPR), ML#2 Dosage (XGBoost), Seq2Seq LSTM forecasting (24h, quantile). |
| **4** | Frontend Dashboard | React 18, Vite, TailwindCSS, Zustand | Real-time dark-mode dashboard, Space selector, threshold band, forecast charts, actuator control, decision timeline. |

## Key Features

- **Real-Time Monitoring:** live telemetry via MQTT, pushed to the frontend with <5 ms latency over STOMP.
- **Space-Aware Thresholds:** ML#1 infers per-room activation thresholds from space parameters (volume, purpose, region) using physics-informed surrogate training.
- **Dosage Control:** ML#2 prescribes the fan level and run duration for the room and the current pollution level.
- **Anticipatory Actuation:** the DL forecast (q90 quantile) warns up to 2 hours before a breach; the loop then actuates automatically with hysteresis, min on/off times and a full decision audit trail.
- **Manual Override:** from the dashboard and from the physical button — always wins over AUTO.
- **CO2 Advisory:** CO2 is **alert-only** (HEPA media cannot remove CO2; ventilation is the only remedy).
- **High Performance:** PostgreSQL native range partitioning, materialized views, batch inserts.
- **Responsive UI:** dark-mode-first React + Recharts dashboard.

## Hardware (as built)

| Part | Detail |
| :--- | :--- |
| MCU | ESP32-S3-DevKitC-1 (N16R8) |
| CO2 / Temp / Hum | Sensirion SCD40 (I2C 0x62, 5 s periodic) |
| PM2.5 | Sharp GP2Y1023AU0F (digital PWM → GPIO 18, 1kΩ/2kΩ divider) |
| Display | SSD1306 0.96" OLED (I2C 0x3C, shared bus @ 400 kHz) |
| Actuator | Intel CPU cooler fan, **4-wire PWM** (12 V constant rail, PWM on the control pin) |
| Filtration | HEPA H13 media on the fan outlet (airtight duct) |
| Power | 12 V PSU → fuse → fan; LM2596 buck → 5 V for the ESP32 |

> One Intel fan delivers ~30–50 m³/h through the filter — sized for rooms ≤ 10 m². Larger rooms need a fan bank.

## Documentation

| Document | Path | Purpose |
| :--- | :--- | :--- |
| **Concept & Architecture (start here)** | `docs/aqss-concept-v2.md` | Thesis, control loop, models, hardware, roadmap |
| **I/O Contract** | `docs/io_contract.md` | Single source of truth for all data contracts |
| Layer 1 Plan (Embedded) | `docs/embedded-layer.md` | Integrated-node firmware |
| Layer 2 Plan (Backend) | `docs/backend-layer.md` | Brain: ingestion, decisions, actuation |
| Layer 3 Plan (AI) | `docs/dl.md` | ML#1 / ML#2 / DL forecast |
| Layer 4 Plan (Frontend) | `docs/fontend-layer.md` | Space-centric dashboard |
| Hardware Datasheet Reference | `docs/hardware_datasheet_reference.md` | Consolidated datasheet specs |
| UI Mockups | `docs/ui_mockups/` | Dashboard and Space Control designs |

## Implementation Roadmap

Phased build with definition-of-done per phase — see `docs/aqss-concept-v2.md` §12:

**Phases 0–4** hardware bring-up (power → I2C → dust → fan PWM → node+MQTT) 🏁 **M1**
**Phases 5–7** basic closed loop (ingestion → space+threshold → AUTO actuation) 🏁 **M2**
**Phases 8–9** intelligence (DL forecast → ML thresholds/dosage) 🏁 **M3**
**Phases 10–11** polish + evidence (full UI → measured CADR + metrics) 🏁 **M4**

## License

MIT — see [LICENSE](LICENSE).
