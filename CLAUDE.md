# AQSS — Project Instructions (pointer)

> **This file is a pointer, not a copy.** The canonical project instructions live in
> **[`AGENTS.md`](./AGENTS.md)** — read that file first. Keeping a single source stops the two files
> drifting apart (they were byte-identical duplicates before V2).

## Project in one paragraph

**AQSS — Closed-Loop Indoor Air Quality Control.** An ESP32-S3 node per room (SCD40 + GP2Y1023 +
OLED + Intel 4-wire PWM fan + HEPA media) measures the air. A Spring Boot backend derives a
**room-specific threshold** from the room's volume, purpose, ventilation and live CO₂, decides when
to run the fan, and records why. CO₂ is an **input to the threshold**, never an actuator — HEPA
cannot remove it. A React dashboard shows the loop and lets the operator take over.
There is no external AI service.

## Non-negotiable rules (full list in `AGENTS.md`)

1. **Backend is 100% the brain.** Nodes measure, display, actuate and report state only.
2. `docs/io_contract.md` is the **single source of truth** — update it first on any cross-layer change.
3. GP2Y1023 ≠ GP2Y1010: digital PWM via `IRAM_ATTR` ISR, valid pulses 6.8–7000 µs, saturates ~390 µg/m³
   reported. Never `analogRead()`.
4. Fan = Intel 4-wire PWM: +12 V constant on pin 2, 25 kHz PWM on pin 4, `min_duty` ≈ 20 % +
   kickstart. **Never PWM the power rail.**
5. ESP32-S3 has **no EEPROM** — persist config in **NVS**.
6. Validation table is identical on every layer: pm25 [0, 500] · co2 [400, 5000] · temp [−10, 60] ·
   hum [0, 100]. A `null` reading is "no sample yet", not a rejection.
7. **Activation never waits.** A breach turns the fan on immediately; motor protection lives on the
   OFF side (min ON 5 min, max ON 120 min).
8. MQTT buffer ≥ 512 B; publish `status` with LWT; null-check `doc["command"]` before `strcmp`.
9. JSON on the wire is **snake_case** everywhere. Copy TypeScript types from real responses.

## Documentation map

| Doc | Purpose |
| :--- | :--- |
| `AGENTS.md` | Canonical instructions, contract summary, conventions |
| `docs/io_contract.md` | **All payloads, topics, schema, endpoints, the threshold formula** |
| `docs/aqss-concept-v2.md` | Thesis, architecture, hardware, limitations |
| `docs/backend-layer.md` | Backend plan (as built) |
| `docs/fontend-layer.md` | Dashboard plan (as built) |
| `docs/embedded-layer.md` | Firmware plan |
| `docs/dl.md` | Why the AI layer was removed, and what it would take to rebuild |
| `docs/deploy.md` | Render + Neon + EMQX runbook |
