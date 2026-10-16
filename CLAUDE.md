# AQSS — Project Instructions (pointer)

> **This file is a pointer, not a copy.** The canonical project instructions live in
> **[`Agents.md`](./Agents.md)** — read that file first. Keeping a single source avoids
> the two files drifting apart (they were byte-identical duplicates before V2).

## Project in one paragraph

**AQSS V2 — Closed-Loop Indoor Air Quality Control.** One integrated ESP32-S3 node per room
(SCD40 + GP2Y1023 + OLED + Intel 4-wire PWM fan + HEPA media) measures indoor air; a Spring Boot
backend (**the brain**) infers a **space-specific threshold** and intervention **dosage** via ML,
forecasts 24 h ahead with a quantile Seq2Seq LSTM, and actuates the fan **before** the threshold
is breached. CO2 is **alert-only** (HEPA cannot remove CO2). The React dashboard exposes the
loop: threshold band, quantile forecast, AUTO/MANUAL control, decision timeline.

## Non-negotiable rules (full list in `Agents.md`)

1. **Backend is 100% the brain.** Nodes measure, display, actuate and report state only.
2. `docs/io_contract.md` is the **single source of truth** — every cross-layer change updates it first.
3. GP2Y1023 ≠ GP2Y1010: digital PWM via `IRAM_ATTR` ISR, valid pulses 6.8–7000 µs, saturates ~750 µg/m³. Never `analogRead()`.
4. Fan = Intel 4-wire PWM: +12 V constant on pin 2, 25 kHz PWM on pin 4, `min_duty` ≈ 20 % + kickstart. **Never PWM the power rail.**
5. ESP32-S3 has **no EEPROM** — persist config in **NVS**.
6. AQI is **display-only** (backend NowCast). Control logic uses raw PM2.5 vs the space threshold.
7. Validation table is identical on every layer: pm25 [0, 500] (reject > 750) · co2 [400, 5000] · temp [−10, 60] · hum [0, 100]. CO2 alert at **1000 ppm**.
8. MQTT buffer ≥ 512 B; publish `status` with LWT; null-check `doc["command"]` before `strcmp`.

## Documentation map

| Doc | Purpose |
| :--- | :--- |
| `Agents.md` | Canonical instructions, contract summary, conventions |
| `docs/aqss-concept-v2.md` | Thesis, architecture, hardware, **roadmap (Phases 0–11)** |
| `docs/io_contract.md` | All payloads, topics, DB schema, endpoints |
| `docs/embedded-layer.md` / `backend-layer.md` / `dl.md` / `fontend-layer.md` | Layer plans (V2) |
