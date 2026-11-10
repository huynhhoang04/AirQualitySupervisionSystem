# AQSS — Closed-Loop Indoor Air Quality Control

> **Version 5.** Three layers, no external AI service. The contract lives in
> [`io_contract.md`](./io_contract.md); this document explains *why* the system is shaped this way.

---

## 1. Problem statement

Indoor air quality monitors tell you a number. They do not tell you whether *this* room, used *this*
way, is unhealthy — and they do not act. A fixed threshold (35 µg/m³, say) is simultaneously too
strict for a well-ventilated living room and too lax for a bedroom where someone sleeps eight hours.

The second problem is worse: even a correct threshold only fires after the air is already bad. A
monitor is a lagging indicator by construction.

## 2. The thesis

**The threshold is a function of the room, and action can precede the breach.**

- A room's tolerance depends on how long someone is in it, how large it is, how well it ventilates,
  and how sealed it is right now. All four are observable; none is a constant.
- The filter must start *before* the room is uncomfortable, not after.

## 3. What the system does about it

| Claim | Mechanism | Where |
| :--- | :--- | :--- |
| Thresholds are room-specific | Dose argument over the room's mass balance, corrected by live CO₂ | `ThresholdService` |
| The operator can see the reasoning | Every factor is returned and displayed | `GET /spaces/{code}/threshold/preview` |
| Action is immediate | Telemetry evaluates on arrival, not on a timer | `ControlLoop.onReading` |
| Decisions are explainable | `threshold_breach` / `hysteresis_off` / `max_on` / `manual` on every action | `devices.last_reason`, `audit_log` |
| The hardware is used for a reason | CO₂ is an input to the threshold, never an actuator | §4.3 |

## 4. The control loop

```
every telemetry message (5 s):
    reading = newest PM2.5
    if unpaired, mode != AUTO, or no room      -> skip
    if reading older than 5 min                -> skip
    if fan OFF and pm25 >= threshold           -> ACTIVATE level 3
    if fan ON  and on >= 120 min               -> DEACTIVATE
    if fan ON  and pm25 < 0.7 x threshold
                 and on >= 5 min               -> DEACTIVATE
    if fan ON  and in AUTO                     -> republish every 5 min (lease)

every 60 s: the same evaluation, as a safety net for silent devices.
```

### 4.1 Why activation does not wait

An earlier version enforced a three-minute minimum OFF window before a breach could turn the fan on.
That is a delay dressed as motor protection: the operator watches the number climb and the machine
does nothing. Protection now lives on the other side of the cycle — once running, the fan stays
running for at least five minutes (no stutter), and never longer than two hours (no seizure).

### 4.2 Why the OFF point differs from the ON point

Turning off at the same level that turns on would make the fan chatter whenever the reading hovers
at the threshold. The hysteresis ratio (0.7) means the room must genuinely clear before the fan
stops, and the minimum ON time means it cannot stop immediately after starting.

### 4.3 CO₂ is an input, never an actuator

HEPA media cannot remove CO₂ — only ventilation can. So CO₂ never triggers the fan. It has one job:
telling the threshold how sealed the room is. CO₂ at 420 ppm is fresh air; at 1200 ppm the room is
occupied and closed, which is exactly when a stricter threshold is right.

This is the reason the node carries a CO₂ sensor at all, and the reason rooms are modelled
separately. Without either, the system would be a PM2.5 monitor with a fan attached.

## 5. The threshold

```
threshold = 15 × √(24 / occupancy_hours) × ∛(V / 20) × f(ventilation) × f(CO₂)   clamped [10, 60]
```

| Term | Value | Why |
| :--- | :--- | :--- |
| `15` | µg/m³ | WHO 2021 24-hour guideline — a dose, not a spike limit |
| `√(24/h)` | 1.0 – 3.5 | Eight hours in a bedroom is a different exposure from two minutes in a lobby |
| `∛(V/20)` | 0.7 – 1.6 | A larger room needs more emitted mass to reach the same concentration |
| `f(ventilation)` | 0.75 – 1.35 | A room that exchanges air on its own tolerates a higher peak |
| `f(CO₂)` | 0.75 – 1.0 | Sealed and occupied right now ⇒ stricter |

Clamped to `[10, 60]`. The ceiling is a health rule rather than a formula output: above 60 µg/m³ the
air is unhealthy for sensitive groups on every index the project quotes.

### 5.1 What was removed, and why

An earlier version had three models in a separate service. See [`dl.md`](./dl.md) for the full
retrospective; the short version:

- **Threshold model** learned a formula and then approximated it — strictly worse than evaluating
  the formula.
- **Dosage model** returned a constant in practice; the constant is now in the code.
- **Forecast** needed 24 h of uninterrupted data to train and had nothing to learn: measured on the
  deployed node, PM2.5 autocorrelation falls to 0.07 at a 60-minute lag, and persistence beat trend
  extrapolation by 18 % RMSE.

## 6. Hardware: the integrated node


### 6.1 Component Connection Map (small → large)

**LEVEL 0 — Discrete components**
```
[ESP32-S3 N16R8]  [SCD40]  [SSD1306 OLED]  [GP2Y1023]  [Intel 4-wire PWM fan]
[LM2596 buck]  [Fuse]  [2N2222 + resistors]  [Button]  [LED]  [HEPA H13 media]
```

**LEVEL 1 — Circuit blocks**
```
① POWER BLOCK
   12V/400W PSU ──[fuse]──┬── Fan pin 2 (+12V constant)
                         └── LM2596 (set to 5.0V) ── ESP32 VIN/5V
   Common GND: PSU ↔ fan ↔ ESP32   (MANDATORY)

② I2C BLOCK (400 kHz, GPIO8 SDA / GPIO9 SCL)
   ESP32 ──┬── SCD40   (0x62)
           └── SSD1306 (0x3C)

③ DUST SENSOR BLOCK
   GP2Y1023 Vo ──[1kΩ/2kΩ divider]── GPIO18 (IRAM_ATTR ISR PWM measurement)

④ FAN CONTROL BLOCK
   ESP32 GPIO_x ──[LEDC 25kHz]──[2N2222 if required]── Fan pin 4 (PWM input)
   Fan pin 2 (+12V constant) · pin 1 (GND) · pin 3 (Tach — reserved)

⑤ INTERFACE BLOCK
   GPIO_y ── Push button (manual override)      GPIO_z ── Status LED
```

**LEVEL 2 — Device node**
```
ESP32-S3 { power + I2C + dust + fan + interface }
FreeRTOS:  Core 0 = Wi-Fi / MQTT / NTP   ·   Core 1 = SCD40 + dust + OLED + fan
```

**LEVEL 3 — Appliance cluster (one room)**
```
Node + Intel fan + HEPA media + airtight enclosure
  → Measures:  CO2 · temperature · humidity · PM2.5
  → Acts:      Fan PWM duty (0–100%, via 4 discrete levels)
```

**LEVEL 4 — Single-room system (end-to-end)**
```
Appliance cluster ──MQTT QoS1──► Mosquitto ──► Backend (BRAIN) ──► PostgreSQL (batch)
                                                   │
                                                   ├──HTTP──► Layer 3 AI (2 ML + 1 DL)
                                                   ├──STOMP─► Layer 4 Frontend
                                                   └──MQTT cmd──► Appliance (set_actuator)
```

**LEVEL 5 — Multi-room scale-out**
```
N appliance clusters (one node per room, each with its own Space profile) ──► one Backend
```

### 6.2 Bill of Materials

**Already owned:** ESP32-S3 DevKitC-1 N16R8 · GP2Y1023AU0F · 1× Intel CPU cooler fan (4-wire PWM, 12V) · 12V/400W PSU.

**Ordered (~649,000 VND):** SSD1306 0.96" I2C OLED (60k) · LM2596S buck module (59k) · HEPA H13 filter media (79k) · SCD40/SCD41 sensor module (451k).

**To buy locally (~50–80k):** push button · LED + 220Ω resistor · fuse + holder (2–3A) · 10kΩ ×2 + 1kΩ ×1 · 2N2222 ×1 · 100µF + 100nF capacitors · terminal blocks + heat-shrink · dupont jumpers · perfboard · fan connector/leads.

**Mechanical (self-made):** frame/duct joining fan ↔ HEPA media, **airtight** (leaks bypass the filter and collapse effective CADR), foam sealing, screws, stand.

### 6.3 Physical Limits (must be stated honestly)

- Intel stock cooler fan: ~85–100 m³/h free-air; **~30–50 m³/h through the HEPA media**.
- ⇒ **One Intel fan suits a small room (~≤10 m²)**. A 20 m² room needs ~240 m³/h ⇒ a bank of 5–6 fans.
- The ~79k HEPA media has **no rated airflow** ⇒ **CADR must be measured experimentally** (Phase 11) — which doubles as report evidence.

### 6.4 Pre-Power Checklist

1. **Set the LM2596 output to exactly 5.0V with a multimeter BEFORE connecting the ESP32** — modules ship unadjusted and can exceed 5V, which destroys the board.
2. OLED: check pin order (Type A GND-VCC vs Type B VCC-GND) — reversed polarity kills the driver IC.
3. HEPA: verify actual dimensions fit the frame; confirm it is genuinely **H13** (not "HEPA-type" = H10/H11).
4. SCD40 vs SCD41: identify the exact chip — SCD41 supports Single-Shot mode, SCD40 does not (periodic 5s only).
5. If the 12V/400W PSU is an ATX unit: short PS_ON (green wire) to GND to switch it on without a motherboard; it also provides a free 5V rail.

---

## 7. Architecture by layer

### 7.1 Room as a first-class entity

`spaces` carries the threshold, the profile it derives from, and where the threshold came from
(`manual` or `profile`). A device points at a room; it does not own a threshold. Swapping a node does
not change the room's policy.

### 7.2 Layer 1 — Embedded

One node per room: SCD40 + GP2Y1023 + SSD1306 + Intel 4-wire fan + HEPA. It measures, displays,
actuates, and reports. It decides nothing — `set_actuator` arrives from the backend. Its local
fail-safe (fan off after 10 minutes without a command in AUTO) exists only for the case where the
backend is unreachable.

The OLED shows the pairing code until the node is linked to a room, then the readings.

### 7.3 Layer 2 — Backend

The brain. Ingestion, validation, room thresholds, the control loop, REST for the dashboard, and
MQTT commands. Details: [`backend-layer.md`](./backend-layer.md).

### 7.4 Layer 3 — Frontend

Four screens around the operator's actual moments: connect, machines, room, settings — plus a
manual. Details: [`fontend-layer.md`](./fontend-layer.md).

## 8. MQTT topics

Full registry with payloads: [`io_contract.md`](./io_contract.md) §1–§5. Four topics, one per
direction of traffic, plus a retained LWT on `status`.

## 9. A worked example

Room: bedroom, 15 m³, low ventilation, CO₂ 900 ppm, PM2.5 42 µg/m³, threshold 17.3.

1. **Threshold.** `15 × √(24/8) × ∛(15/20) × 0.75 × f(900)` = `15 × 1.73 × 0.91 × 0.75 × 0.88` ≈
   **16.6 µg/m³**.
2. **Decision.** 42 ≥ 16.6 and the fan is off → ACTIVATE, level 3, `reason = threshold_breach`.
3. **Node.** Receives `set_actuator{state:ON, level:3, mode:AUTO}`, applies 90 % duty with a kickstart
   pulse, publishes `actuator/state`.
4. **Dashboard.** The room screen reads `last_reason` and shows:
   *"Đang lọc · tự bật 5 phút trước · vì bụi vượt ngưỡng"*.
5. **Stop.** When PM2.5 falls below `0.7 × 16.6 = 11.6` and the fan has run five minutes →
   DEACTIVATE, `reason = hysteresis_off`.

## 10. What the report can claim

| Claim | Evidence |
| :--- | :--- |
| Thresholds differ by room | Formula + a table of computed values across room types and sizes |
| Action is immediate | Telemetry-triggered evaluation, not a polling timer |
| Every decision is explainable | `last_reason` on the device, one sentence on screen, full trail in `audit_log` |
| The hardware is justified | CO₂ changes the threshold; without it the formula loses an input |
| The fan does something measurable | Filter hours counted on the node; CADR measurable in Phase 11 |
| The AI layer was removed for a reason | `dl.md`, with the autocorrelation and baseline measurements that justified it |

### 10.1 Honest limitations

- The threshold constants are policy, not measurements. The dose target is WHO's; the occupancy
  hours, ventilation multipliers and volume exponent are engineering choices, documented and
  adjustable in one file.
- One Intel fan suits a room up to about 10 m². Larger rooms need a fan bank.
- The dust sensor saturates near 393 µg/m³; readings at that point are a floor, and the flag travels
  with the value.
- There is no multi-user access control. The dashboard is open by design on a private deployment.
