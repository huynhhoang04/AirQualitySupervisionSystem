# Air Quality Supervision System (AQSS)

A closed-loop indoor air quality controller. An ESP32-S3 node measures a room; the backend decides
when to run the filter fan and records why; the dashboard shows the loop and lets the operator take
over.

> **The premise:** the level at which a room becomes unhealthy is not one number. A bedroom slept in
> eight hours and a lobby crossed in two minutes cannot share a threshold. AQSS derives it from the
> room's own volume, purpose, ventilation, and live CO₂ — then acts on it before the room is bad.

## Architecture

| Layer | Stack | Role |
| :---: | :--- | :--- |
| 1 · `embedded/` | ESP32-S3, C++17, FreeRTOS | SCD40 + GP2Y1023 sensing, OLED, 4-wire PWM fan, MQTT. Measures and actuates; decides nothing |
| 2 · `backend/` | Java 21, Spring Boot 3.3, PostgreSQL, Paho MQTT | Ingestion, room thresholds, the control loop, REST. **The brain** |
| 3 · `frontend/` | React 19, TypeScript, Vite, TailwindCSS | Machines list, room view, settings, pairing flow |

There is no separate AI service. An earlier version had one; it was removed when the threshold
turned out to be a formula over the room profile, and the forecast had nothing to learn from at a
5-second cadence. `docs/io_contract.md` §7.2 documents what replaced it.

## What it does

- **Room-derived thresholds.** `15 × √(24/occupancy) × ∛(V/20) × f(ventilation) × f(CO₂)`, clamped
  to `[10, 60]` µg/m³. The dashboard shows every factor, so the number is explained rather than
  asserted.
- **Immediate actuation.** Telemetry evaluates the moment it arrives; a breach turns the fan on
  without waiting. Motor protection is on the OFF side: minimum 5 min on, maximum 2 h.
- **Explained decisions.** Every activation records why (`threshold_breach`, `hysteresis_off`,
  `max_on`, `manual`) and the room screen says it in one sentence.
- **Filter life.** The node counts fan hours; the dashboard shows what is left and offers a reset.
- **Pairing by code.** The OLED shows a six-character code; the operator types it into the dashboard
  and names the room. Unpairing tells the node to forget, so the code comes back.
- **CO₂ advisory.** Shown, stored, and used as a threshold input — never actuated, because HEPA
  cannot remove it.

## Hardware (as built)

| Part | Detail |
| :--- | :--- |
| MCU | ESP32-S3-DevKitC-1 (N16R8) |
| CO₂ / temp / humidity | Sensirion SCD40 (I2C `0x62`, 5 s periodic) |
| PM2.5 | Sharp GP2Y1023AU0F (digital PWM → GPIO 18, 1 kΩ/2 kΩ divider) |
| Display | SSD1306 0.96″ OLED (I2C `0x3C`, shared 400 kHz bus) |
| Actuator | Intel CPU cooler fan, 4-wire PWM (12 V constant, PWM on the control pin) |
| Filtration | HEPA H13 on the fan outlet, airtight duct |
| Power | 12 V PSU → fuse → fan; LM2596 buck → 5 V for the ESP32 |

One Intel fan delivers roughly 30–50 m³/h through the filter — sized for rooms up to about 10 m².

## Running it

```bash
# backend (needs PostgreSQL)
cd backend && ./mvnw spring-boot:run

# dashboard
cd frontend && npm install && npm run dev

# firmware
cd embedded && pio run -e esp32-s3-devkitc-1 -t upload
```

Deployment (Render + Neon + EMQX): [`docs/deploy.md`](docs/deploy.md).

## Documentation

| Document | Purpose |
| :--- | :--- |
| **[`docs/io_contract.md`](docs/io_contract.md)** | **Single source of truth: payloads, tables, endpoints, the threshold formula** |
| [`AGENTS.md`](AGENTS.md) | Working instructions, hardware rules, conventions |
| [`docs/aqss-concept-v2.md`](docs/aqss-concept-v2.md) | Thesis and architecture |
| [`docs/embedded-layer.md`](docs/embedded-layer.md) | Firmware plan |
| [`docs/backend-layer.md`](docs/backend-layer.md) | Backend plan |
| [`docs/fontend-layer.md`](docs/fontend-layer.md) | Dashboard plan |
| [`docs/deploy.md`](docs/deploy.md) | Deployment runbook |
| [`docs/hardware_datasheet_reference.md`](docs/hardware_datasheet_reference.md) | Datasheet figures |

## License

MIT — see [LICENSE](LICENSE).
