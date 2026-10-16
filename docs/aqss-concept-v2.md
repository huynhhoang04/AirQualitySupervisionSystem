# AQSS V2 — Closed-Loop Indoor Air Quality Control System

> **Version:** 2.1 — Concept & Architecture Document
> **Date:** 2026-09-16
> **Status:** Draft for End-of-Week Report
> **Audience:** Academic reviewers, project stakeholders, team members
> **Prerequisite:** None — this document is self-contained.

---

## 1. Problem Statement

Indoor air quality (IAQ) directly affects human health, cognitive performance, and sleep quality. The World Health Organization (WHO 2021) sets PM2.5 annual mean guidelines at **5 µg/m³** — a value rarely achieved in Vietnamese indoor environments without active intervention.

**The gap:** Existing low-cost IAQ monitors are *passive observation tools*. They display numbers and sometimes trigger alarms, but they do not *act*. The human occupant must interpret sensor readings, decide whether the air is "bad enough" to do something, and manually turn on a purifier or open a window. Worse, by the time a threshold is crossed, the occupant has already been exposed for the duration of the ramp-up.

**The thesis of AQSS V2:**

> Air quality thresholds are not fixed numbers — they are functions of the space. The system infers thresholds and intervention dosages per-space, then acts *before* the threshold is breached.

If the system only reacts *after* a fixed threshold is crossed (the V1 paradigm), the Deep Learning module becomes decorative — a simple `if (pm25 > 150) turn_on_purifier()` suffices, and all the LSTM/Attention machinery is wasted. The true value of the DL module lies in **early warning and anticipatory intervention** — acting before the pollution peak arrives.

---

## 2. V1 vs V2: What Changes

| Dimension | V1 (Current) | V2 (Proposed) |
| :--- | :--- | :--- |
| **Positioning** | Passive supervision (monitoring) | Closed-loop control with actuation |
| **Scope** | Ambient / outdoor | Indoor, per-space |
| **Thresholds** | Global constants (e.g., 150 µg/m³ for all rooms) | Space-derived functions of volume, purpose, region |
| **Actions** | None (display only) | Soft alert (OLED) + hard intervention (PWM-controlled filter fan) |
| **ML/DL role** | Decorative (thresholds are hardcoded, DL adds no value beyond `if/else`) | Essential and irreplaceable: threshold inference, dosage calculation, early warning |
| **AQI usage** | Primary metric — computed from instantaneous PM2.5 using EPA 24h breakpoints (incorrect for real-time) | Display-only indicator computed via NowCast; control logic uses raw PM2.5 + space-derived thresholds |
| **Node topology** | Sensor node only | **Single integrated node**: sensing + display + fan control on one ESP32-S3 |

---

## 3. Core Thesis: Why Fixed Thresholds Fail Indoors

Consider two rooms in the same city:

| Property | Room A (Student dorm) | Room B (University lecture hall) |
| :--- | :--- | :--- |
| Volume | 15 m³ (5 m² × 3 m ceiling) | 450 m³ (150 m² × 3 m ceiling) |
| Purpose | Sleeping (8h continuous exposure) | Lecture (2h intermittent exposure) |
| Occupancy | 1 person | 50 people |

Applying the same 150 µg/m³ PM2.5 threshold to both rooms is **physically wrong**:

- In Room A, 150 µg/m³ in a 15 m³ volume with 8 hours of exposure yields a cumulative dose of:
  $$D_A = C \times V \times t = 150 \times 15 \times 8 = 18{,}000 \text{ µg·m³·h}$$

- In Room B, even at the same concentration, shorter exposure and higher dilution volume yield:
  $$D_B = 150 \times 450 \times 2 = 135{,}000 \text{ µg·m³·h}$$

  But because the ACH (air changes per hour) in a lecture hall is typically 6–10× higher than a sealed dorm room, Room B's actual occupant exposure is *far lower* than Room A's.

**Conclusion:** The activation threshold for Room A should be significantly *lower* than for Room B. The system must learn this mapping from space parameters.

---

## 4. The Control Loop

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                        SPACE PROFILE                                   │
  │  (volume_m³ · purpose · region · ventilation_estimate)                 │
  └──────────────┬────────────────────────────────┬─────────────────────────┘
                 │                                │
                 ▼                                ▼
         ┌────────────────┐               ┌────────────────┐
         │  ML#1 Threshold │               │  ML#2 Dosage   │
         │  (GP Regression)│               │ (GB Regression) │
         └───────┬────────┘               └───────┬────────┘
                 │                                │
                 ▼                                ▼
         ┌────────────┐                   ┌────────────────┐
         │ Base       │                   │ Filter Level + │
         │ Threshold  │                   │ Run Duration   │
         └──────┬─────┘                   └───────┬────────┘
                │                                 │
                │    ┌────────────────────────┐   │
                └───►│  DECISION ENGINE       │◄──┘
                     │  (Backend Service)      │
   PM2.5  ──────────►│                        │
   (real-time)       │  Compare:              │
                     │  pm25 vs threshold     │
   DL Forecast ─────►│  forecast vs threshold │
   (24h ahead)       │                        │
                     └───────┬──────────┬─────┘
                             │          │
                             ▼          ▼
                  ┌──────────────┐  ┌──────────────────┐
                  │ SOFT ALERT   │  │ HARD INTERVENTION │
                  │ (OLED warn)  │  │ (PWM → filter fan)│
                  │ Before breach│  │ At/after breach   │
                  └──────────────┘  └──────────────────┘
```

### 4.1 Decision Logic (Backend `ControlDecisionService`)

```
Every control_cycle (60 seconds):
    current_pm25 ← latest MQTT reading (or rolling 5-min average)
    forecast_24h ← cached DL forecast (refreshed hourly)
    threshold    ← cached ML#1 output for this space
    dosage       ← cached ML#2 output for this space

    hours_to_breach ← first index i where forecast_24h[i].pm25_q90 > threshold

    IF current_pm25 >= threshold:
        → ACTIVATE filter fan at dosage.level for dosage.duration_minutes
        → mode = AUTO
        → publish aqss/devices/{id}/command {set_actuator, state: ON, level: dosage.level}

    ELSE IF hours_to_breach <= 2:
        → WARN on OLED: "Air quality declining. Forecast breach in {hours_to_breach}h."
        → publish aqss/devices/{id}/command {display_warning: "..."}
        → mode = ALERT (awaiting manual confirmation or auto-escalation)

    ELSE:
        → IDLE
        → IF fan currently running AND current_pm25 < threshold × 0.7:
            → DEACTIVATE fan (hysteresis: 70% of threshold to prevent chatter)
```

### 4.2 Safety Constraints

| Constraint | Value | Rationale |
| :--- | :--- | :--- |
| Minimum ON time | 5 minutes | Prevent actuator chatter damage |
| Minimum OFF time | 3 minutes | Allow motor cooldown |
| Maximum continuous ON | 120 minutes | Prevent filter/fan overheating |
| Hysteresis band | 70% of threshold | Prevent oscillation at boundary |
| Manual override priority | Always | User can always override AUTO (dashboard button **and** physical button) |
| Fail-safe on network loss | Fan OFF after 10 min | No runaway operation without backend brain |
| Audit log | Every decision | Full traceability for debugging |

### 4.3 CO2 Handling — Alert Only (Explicit Design Decision)

The HEPA media removes **particulate matter only — it does not remove CO2**. CO2 is reduced exclusively by **ventilation** (fresh-air exchange), and ventilating a sealed room in a polluted city (Hanoi) trades CO2 down for PM2.5 up. Therefore:

- **CO2 above threshold (1,000 ppm indoor guideline; ~420 ppm outdoor baseline) → ALERT ONLY.**
- CO2 generates an OLED/dashboard warning and a ventilation *recommendation* ("open the window").
- **CO2 never enters the automatic actuation path.** No auto-actuation for CO2 in V2.

This keeps the control thesis clean (PM2.5 + filter fan) and avoids a physically incorrect loop.

---

## 5. The Three Models: Inputs, Outputs, and Training

### 5.1 ML#1 — Threshold Inference (Space → Base PM2.5 Threshold)

| Property | Value |
| :--- | :--- |
| **Problem type** | Tabular regression |
| **Algorithm** | Gaussian Process Regression (GPR) or Gradient Boosting (XGBoost) |
| **Input features** | `volume_m3`, `purpose_encoded`, `region_encoded`, `ventilation_estimate`, `occupancy_typical` |
| **Output** | `base_threshold_pm25` (µg/m³) — the PM2.5 concentration at which intervention should begin |
| **Uncertainty** | GPR natively produces `µ ± σ` → principled confidence interval (unlike V1's Linear+ReLU fake CI) |

**Training data source (the critical question):**

There is no off-the-shelf dataset that maps `(volume, purpose, region)` → PM2.5 threshold. We solve this with a **physics/standard-informed surrogate strategy:**

1. **Mass-balance model for indoor PM2.5:**
   $$C_{indoor}(t) = C_{outdoor} \cdot P + \frac{S}{V \cdot \lambda_{total}}$$
   Where:
   - $P$ = penetration factor (0.5–0.9 depending on building tightness)
   - $S$ = indoor emission source strength (µg/h)
   - $V$ = room volume (m³)
   - $\lambda_{total}$ = total air exchange rate (h⁻¹) = natural ventilation + mechanical ventilation + deposition

2. **Standards-derived targets:**
   - WHO 2021 annual guideline: 5 µg/m³ (aspirational)
   - WHO 2021 24-hour guideline: 15 µg/m³ (practical target)
   - QCVN 05:2023 (Vietnam): 25 µg/m³ (24-hour average)
   - Adjust by exposure duration: sleeping (8h) gets a lower threshold than transient spaces (1h).

3. **Surrogate generation:**
   - Enumerate a grid of `(volume, purpose, region, ventilation)` combinations.
   - For each combination, solve the steady-state mass-balance equation given regional outdoor PM2.5 data (from OpenAQ).
   - Set the target threshold as the PM2.5 level at which cumulative exposure over the expected occupancy duration exceeds WHO guidelines.
   - This produces ~10,000 synthetic training pairs.
   - The ML model learns the fast approximation; when real sensor data arrives, it self-corrects via online fine-tuning.

**Why ML and not just the formula?** The mass-balance model requires precise knowledge of $P$, $S$, $\lambda$, which are expensive to measure. The ML model learns to approximate these latent parameters from observable space characteristics, and improves as real data accumulates.

### 5.2 ML#2 — Dosage Calculation (Space + Current Level → Intervention Prescription)

| Property | Value |
| :--- | :--- |
| **Problem type** | Tabular regression (multi-output) |
| **Algorithm** | Gradient Boosting (XGBoost) — fast, handles tabular data well |
| **Input features** | `volume_m3`, `purpose_encoded`, `current_pm25`, `base_threshold`, `outdoor_pm25_estimate`, `filter_cadr` |
| **Output** | `filter_level` (0–3, where 0=off, 1=low, 2=medium, 3=high), `run_duration_minutes` |
| **Interpretation** | "In this room, at this pollution level, run the filter at level 2 for 45 minutes to bring PM2.5 below threshold." |

**Mapping to the physical actuator (Intel 4-wire PWM fan):** firmware maps `filter_level` to PWM duty — `0 → 0%`, `1 → 35%`, `2 → 60%`, `3 → 90%` — consistent with measured OEM fan duty curves (low/medium/high ≈ 35.7% / 56% / 92% on a commercial purifier). Duty below ~20% can stall a BLDC fan, hence the discrete levels and a firmware-enforced `min_duty`.

**Training data source:**

Again generated via physics model:
$$t_{clean} = \frac{V}{CADR_{effective}} \times \ln\left(\frac{C_{current} - C_{outdoor} \cdot P}{C_{target} - C_{outdoor} \cdot P}\right)$$

Where $CADR_{effective}$ depends on the filter level setting. We compute the minimum `(level, duration)` pair that achieves $C_{target} < threshold$ for each scenario in the grid.

### 5.3 DL — Forecasting Engine (Time-Series → 24h PM2.5 Sequence)

| Property | Value |
| :--- | :--- |
| **Problem type** | Sequence-to-sequence time-series regression |
| **Architecture** | Attention-augmented Seq2Seq LSTM (Encoder-Decoder) |
| **Input** | 24 hours of PM2.5 data resampled to **15-minute intervals** = 96 time steps × 10 features |
| **Output** | 24 hours ahead = 96 time steps × `[pm25_q50, pm25_q10, pm25_q90]` |
| **Uncertainty** | **Quantile regression loss** (τ = 0.1, 0.5, 0.9) → true prediction intervals, not fake CI |
| **Pre-training** | OpenAQ Southeast Asia + UCI Beijing PM2.5 |
| **Fine-tuning** | AQSS device data (when available) |

**Critical change from V1:**

| Aspect | V1 | V2 |
| :--- | :--- | :--- |
| Input window | 6AM to now (variable) + last 1h | Rolling 24h (fixed 96 steps @ 15min resample) |
| Output | 24 scalar points (1 per hour) | 96 points (15-min resolution) × 3 quantiles |
| Confidence | Fake — `ReLU(Linear(hidden))` producing `conf_upper/lower` | Principled — Quantile Loss at τ=0.1, 0.5, 0.9 |
| Autoencoder evaluator | Present (anomaly detection) | **Removed** — not part of closed-loop control thesis |
| Resample | None (raw 5s data → millions of points) | 15-minute median aggregation before model input |

**Why remove the Autoencoder evaluator?**

In V1, the Autoencoder was designed to detect sensor anomalies. In V2, sensor health is a monitoring concern, not a control concern. The closed-loop thesis focuses on:
1. Infer threshold per space (ML#1)
2. Calculate dosage (ML#2)
3. Forecast and warn early (DL)

Sensor anomaly detection can be re-added later as an orthogonal module, but including it in the core loop dilutes the thesis and adds complexity that cannot be defended in a report without clear integration into the control decision.

### 5.4 Quantile Loss Function (Replacing MSE + Fake CI)

$$\mathcal{L}_\tau(y, \hat{y}) = \begin{cases} \tau \cdot (y - \hat{y}) & \text{if } y \geq \hat{y} \\ (1 - \tau) \cdot (\hat{y} - y) & \text{if } y < \hat{y} \end{cases}$$

Total loss = $\mathcal{L}_{0.1} + \mathcal{L}_{0.5} + \mathcal{L}_{0.9}$

This produces three output curves:
- **q10:** 10th percentile (optimistic bound)
- **q50:** median prediction (best estimate)
- **q90:** 90th percentile (pessimistic bound)

The decision engine uses **q90** (worst-case) for safety-critical early warning, and **q50** for display.

---

## 6. Hardware: The Integrated Node (As-Built Configuration)

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

## 7. Architecture Changes by Layer

### 7.1 New First-Class Entity: Space

A **Space** is the fundamental unit of V2. All thresholds, dosages, and control decisions are scoped to a Space — never to a raw device.

```
Space (phòng trọ "201")
├── Integrated Node: AQSS_ESP32S3_01 (SCD40 + GP2Y1023 + OLED + Intel fan PWM)
├── Derived Threshold: 18 µg/m³ (from ML#1, with GP uncertainty band)
├── Dosage Profile: Level 2 for 25 min (from ML#2)
└── Control Decisions: [2026-09-16T23:00 ALERT, 2026-09-17T00:15 AUTO ON, ...]
```

### 7.2 Layer 1 — Embedded (Single Integrated Node)

One ESP32-S3 performs sensing, display, and actuation. FreeRTOS: Core 0 = Wi-Fi/MQTT/NTP; Core 1 = SCD40 + dust + OLED + fan.

**Firmware responsibilities:**

- **Fan control:** PWM via LEDC @ 25 kHz on the fan's PWM input pin (12V rail stays constant). **Kickstart** pulse (100% for ~200 ms) before settling on the target duty; firmware-enforced `min_duty` (~20%) to avoid BLDC stall.
- **OLED as a warning channel:** receives `display_warning` commands from the backend and renders forecast-based early warnings *before* the threshold is breached — not just local sensor values.
- Telemetry payload gains **`space_id`**.
- **Publishes `actuator/state`** (`duty`, `level`, `mode`, `filter_runtime_h` — cumulative runtime for filter-life tracking).
- **Publishes `status` + Last Will and Testament** (see §8.2) — V1 subscribed to this topic but never published it.
- **Physical manual override button** — always takes priority over network commands.
- **Fail-safe:** if no command received for 10 minutes, fan OFF.
- Watchdog timer: 30 seconds, auto-reboot on hang.

### 7.3 Layer 2 — Backend (The Brain)

**New database tables (`V4__spaces_and_actuation.sql`):**

| Table | Purpose |
| :--- | :--- |
| `spaces` | Space registry (id, name, volume_m3, purpose, region, created_at) |
| `space_devices` | Many-to-many: which nodes belong to which space |
| `space_thresholds` | Cached ML#1 output per space (recalculated when space config changes) |
| `actuators` | Actuator registry (device_id, space_id, current_state, last_command_at) |
| `control_decisions` | Audit log of every decision (space_id, timestamp, trigger, action, mode, threshold_used, dosage_used) |

**New services:**

| Service | Responsibility |
| :--- | :--- |
| `SpaceService` | CRUD for spaces, assign/remove devices |
| `ThresholdService` | Call ML#1, cache result in `space_thresholds`, invalidate on space config change |
| `DosageService` | Call ML#2 with current readings + cached threshold → intervention prescription |
| `ControlDecisionService` | The **brain** — evaluates readings + forecast vs threshold → decide ALERT / AUTO / IDLE |
| `ActuatorService` | Publish `set_actuator` commands via MQTT, track actuator state from `actuator/state` topic |

**New STOMP topics:**

| Topic | Payload | Purpose |
| :--- | :--- | :--- |
| `/topic/spaces/{id}/decision` | `{action, mode, trigger, threshold, dosage, timestamp}` | Real-time control decision stream |
| `/topic/actuators/{id}` | `{state: ON/OFF, level, since, mode}` | Actuator state updates |

**Removed from V1:**

| Item | Reason |
| :--- | :--- |
| `model_evaluations` table | Autoencoder evaluator removed from core loop |
| Redis service in docker-compose | Never used in any service; remove to reduce complexity |
| `aqi_level VARCHAR(30)` | Changed to `VARCHAR(40)` — "Unhealthy for Sensitive Groups" is exactly 30 chars, zero headroom |

### 7.4 Layer 3 — AI Service (3 Models)

**New endpoints:**

| Endpoint | Method | Model | Input | Output |
| :--- | :--- | :--- | :--- | :--- |
| `/api/v1/threshold` | POST | ML#1 (GPR) | Space profile JSON | `{base_threshold_pm25, uncertainty_lower, uncertainty_upper}` |
| `/api/v1/dosage` | POST | ML#2 (XGBoost) | Space + current readings + threshold | `{filter_level, run_duration_minutes}` |
| `/api/v1/forecast` | POST | DL (Seq2Seq LSTM) | 96-step time series (24h @ 15min) | 96-step forecast × 3 quantiles |

**Removed from V1:**

| Item | Reason |
| :--- | :--- |
| `/api/v1/evaluate` endpoint | Autoencoder evaluator removed |
| `/api/v1/predict` endpoint | Renamed to `/api/v1/forecast` for clarity |
| `conf_upper` / `conf_lower` from Linear+ReLU | Replaced by principled quantile outputs (q10, q50, q90) |

**DL input resample strategy (critical for performance):**

Raw data arrives at 5-second intervals = 17,280 readings per day. Feeding this directly into an LSTM is computationally prohibitive and contains redundant noise.

**Solution:** Resample to 15-minute median aggregation before model input.
- 24h ÷ 15min = **96 time steps** (manageable sequence length for LSTM)
- Median (not mean) is robust to sensor glitches
- Backend performs the resampling before calling `/api/v1/forecast`

### 7.5 Layer 4 — Frontend (New Screens)

**New page: Space Management (`/spaces`)**
- CRUD interface for creating/editing spaces (name, volume, purpose, region).
- Assign nodes to spaces.
- Display ML#1-derived threshold (with GP uncertainty band) and ML#2-derived dosage profile.

**New page: Space Control View (`/spaces/:id`)**
- Real-time PM2.5 vs threshold line (threshold shown as horizontal reference line with uncertainty band).
- 24h forecast overlay with q10/q50/q90 shaded bands (both bands drawn — V1 only drew the upper one).
- Actuator control panel: AUTO / MANUAL toggle, current state indicator (ON/OFF/level), manual level slider.
- Decision timeline: chronological list of all `ControlDecision` events with expandable detail (trigger, threshold used, dosage prescribed).

**Modified: Dashboard (`/`)**
- Space selector dropdown (replacing device selector).
- AQI gauge now shows NowCast-calculated AQI (display-only).
- New "Early Warning" banner: appears when DL forecast predicts breach within 2 hours.

---

## 8. MQTT Topic Registry (V2, Complete)

| Topic | Direction | QoS | Retain | Payload | V1? |
| :--- | :---: | :---: | :---: | :--- | :---: |
| `aqss/devices/{id}/telemetry` | Device → Server | 1 | No | Sensor JSON (+ `space_id`) | ✅ |
| `aqss/devices/{id}/status` | Device → Server | 1 | **Yes** | `{"status": "ONLINE/OFFLINE"}` | ❌ NEW |
| `aqss/devices/{id}/command` | Server → Device | 1 | No | `{command, params}` — adds `set_actuator`, `display_warning` | ✅ |
| `aqss/devices/{id}/actuator/state` | Device → Server | 1 | No | `{duty, level, mode, filter_runtime_h, ts}` | ❌ NEW |

### 8.2 LWT Implementation (fixing the V1 gap)

```cpp
mqttClient.connect(clientId, user, pass,
    "aqss/devices/AQSS_ESP32S3_01/status",  // will topic
    1,                                        // will QoS
    true,                                     // will retain
    "{\"status\":\"OFFLINE\"}"                // will payload
);
// On successful connect: publish {"status":"ONLINE"} with retain=true.
```

---

## 9. Numerical Walkthrough: Proof of Concept

**Scenario:** Student dorm room "201" in Cau Giay, Hanoi.

### Step 1: Space Profile

| Parameter | Value |
| :--- | :--- |
| Name | Phòng 201 |
| Area | 5 m² |
| Ceiling height | 3 m |
| Volume | **15 m³** |
| Purpose | Sleeping (bedroom) |
| Region | Hanoi (Cau Giay) |
| Typical outdoor PM2.5 | ~35 µg/m³ (Hanoi average) |
| Ventilation estimate | Low (sealed room, no mechanical vent) |
| Fan + HEPA effective CADR | ~40 m³/h (to be measured in Phase 11) |

### Step 2: ML#1 Threshold Inference

Input: `{volume: 15, purpose: "bedroom", region: "hanoi", ventilation: "low", occupancy: 1}`

Using the mass-balance surrogate:
- Penetration factor P = 0.8 (typical Vietnamese apartment, gaps in windows)
- Indoor source S ≈ 0 (non-smoker, no cooking)
- Natural ventilation λ_nat ≈ 0.3 h⁻¹
- Steady-state indoor PM2.5 ≈ C_outdoor × P = 35 × 0.8 = **28 µg/m³**

WHO 24h guideline = 15 µg/m³. For 8h sleeping exposure, we want cumulative dose below the WHO target.

**ML#1 Output:** `{base_threshold: 18 µg/m³, uncertainty: [15, 22]}` (conservative for sleeping)

### Step 3: Current Readings (from MQTT)

At 22:00, the latest telemetry shows:
- PM2.5 = 12 µg/m³ (below threshold ✅)
- CO2 = 520 ppm
- Temp = 28°C, Humidity = 72%

### Step 4: DL Forecast (24h ahead)

The Seq2Seq LSTM predicts the next 24 hours (96 steps @ 15min):

| Time | q10 | q50 | q90 |
| :--- | :--- | :--- | :--- |
| 22:15 | 11 | 13 | 16 |
| 22:30 | 12 | 14 | 17 |
| ... | ... | ... | ... |
| 01:00 | 15 | 19 | **24** |
| 01:15 | 16 | 20 | **26** |
| 02:00 | 14 | 18 | 22 |
| 06:00 | 10 | 12 | 15 |

The q90 (worst-case) crosses the threshold (18 µg/m³) at **~00:30** (2.5 hours from now).

### Step 5: Decision Engine

```
current_pm25 = 12 < threshold = 18     → NOT breached
hours_to_breach(q90) = 2.5             → > 2 hours → not yet in ALERT window
```

**Decision: IDLE.** No action. But the system is watching.

At **22:30**, the next cycle runs. Forecast now shows q90 breach at 01:00 (2.5h away). Still IDLE.

At **23:00**, forecast shows q90 breach at 00:45 (**1.75 hours away** — within 2h window):

**Decision: SOFT ALERT.**
- Push `display_warning` command to node OLED: "⚠ Air quality may decline by 01:00. Consider turning on the filter."
- STOMP push to frontend `/topic/spaces/201/decision`: `{action: "ALERT", reason: "forecast_breach_q90", hours_to_breach: 1.75}`
- User sees the warning on the dashboard and OLED. They can manually activate the fan or wait.

At **00:15**, real PM2.5 has risen to **19 µg/m³** (above threshold 18):

**Decision: AUTO ACTIVATE.**
- ML#2 Dosage: `{filter_level: 2 (medium), run_duration: 25 min}` (15 m³ room at 19 µg/m³, ~40 m³/h effective CADR)
- Publish to node: `{command: "set_actuator", params: {state: "ON", level: 2}}`
- Log to `control_decisions`: `{space_id: 201, action: "ACTIVATE", mode: "AUTO", threshold: 18, dosage: {level: 2, duration: 25}, trigger: "threshold_breach"}`

At **00:40**, PM2.5 has dropped to **11 µg/m³** (below threshold × 0.7 = 12.6):

**Decision: AUTO DEACTIVATE.**
- Publish: `{command: "set_actuator", params: {state: "OFF"}}`
- Log decision.

The occupant slept through the entire episode. The system handled it autonomously.

---

## 10. Bug Fixes & Contract Corrections from the V1 Audit

The following inconsistencies in V1 were discovered during the V2 architecture review and must be corrected regardless of V2 adoption.

### 10.1 Layer 1 (embedded-layer.md)
- `aqi_level[16]` buffer overflow — the contract permits a 30-character string. Widen to ≥32.
- `recalibrate_dust` writes "EEPROM" — the ESP32-S3 has **no EEPROM**; use **NVS**.
- Pulse filter accepts `<= 7500 µs` — datasheet max is 7,000 µs.
- PM2.5 valid range declared 0–1000 — the GP2Y1023 saturates at ~750 µg/m³.
- AQI computed from *instantaneous* PM2.5 against 24h-averaged EPA breakpoints — incorrect. V2: control uses raw PM2.5 + space threshold; AQI is display-only via NowCast.
- `mqttCallback` crashes when `command` is absent (`strcmp(nullptr, ...)`).
- Both `Wire.begin()` and the U8g2 constructor initialise the same I2C peripheral — share one `Wire` instance.
- `jsonBuffer[384]` has no headroom — adding `space_id` will overflow it.
- Watchdog: the doc instructs feeding it, the sample code never does.

### 10.2 Layer 2 (backend-layer.md)
- **Monthly partitions are never auto-created** — `create_monthly_partition()` exists but nothing schedules it; the pre-created partitions end 2026-12, so inserts start failing in 2027-01.
- `dropOldPartitions()` is wrong: it derives a partition name from `now − 90 days` and drops it — this can drop a partition still holding data newer than 90 days.
- **Entity/table PK mismatch**: table PK is `(id, recorded_at)` (composite) but `TelemetryData` declares `@Id` on `id` only with `@GeneratedValue(IDENTITY)` → `ddl-auto: validate` fails at startup.
- Redis sits in docker-compose but is referenced by no service.
- MQTT runs plaintext on 1883 (no TLS); port 9001 is exposed but unused; auth defaults are empty in `application.yml` while `mosquitto.conf` defines credentials.
- `device_id` examples are inconsistent (`ESP32S3_AQSS_01` in SQL vs `AQSS_ESP32S3_01` in the contract).
- Hibernate `dialect` is set manually (auto-detected in Hibernate 6); no dev profile for `JWT_SECRET`.

### 10.3 Layer 3 (dl.md)
- Input is infeasible and redundant: `data_since_6am` (~12,960 points) + `data_last_hour` (720) are sent together despite being a union. V2's rolling 24h must be **resampled** (96 steps @ 15 min).
- "95% confidence interval" is fake: output is `nn.Linear(hidden, 3)` + `ReLU` — no uncertainty mechanism, and ReLU clips negative bounds. Replaced by **quantile loss**.
- Loss references `MSE_hour`/`MSE_day` but the architecture has a single 24-point output head; λ weights undefined.
- The Autoencoder diagram shows a "Feature Extractor CNN" while the code is LSTM-only.
- `reference_window` is a dead input — the model consumes only the evaluation window.
- Anomaly threshold τ is never defined.
- `RobustScaler` is not constrained to be fit on the training split only.
- Transfer learning from UCI Beijing is weak — that dataset has no CO2 feature.
- The Dockerfile may re-install the CUDA torch build via `requirements.txt`.
- `config.yaml` defines `seq_length_eval` but no predictor sequence length.

### 10.4 Layer 4 (fontend-layer.md)
- The two mockup images are **swapped** relative to their captions (Dashboard ↔ Forecasting).
- REST calls carry no `Authorization` header and the STOMP client sets no `connectHeaders` — the frontend cannot authenticate at all.
- `useWebSocket` stores each message in `useState`, contradicting the stated strategy of using a ref/Zustand to avoid re-renders.
- WebSocket URL is hardcoded to `http://localhost:8080/ws`; no env config; no Vite dev proxy.
- The forecast chart draws `confidence_upper` but not `confidence_lower` — a misleading band.
- `tailwind.config.js` uses CommonJS in a Vite + TypeScript stack.

### 10.5 Cross-layer
- README links to `docs/plan_layer1_embedded.md` (and siblings) which do not exist — actual files are `embedded-layer.md`, `backend-layer.md`, `dl.md`, `fontend-layer.md`.
- `Agents.md` and `Claude.md` are byte-identical duplicates; `install-agents.sh`, `ci.yml`, and `License` are empty; `License` duplicates `LICENSE`.
- Temperature validation ranges disagree across layers (`< -40 / > 85` vs `[-20, 80]` vs `[-10, 60]`) — unify.
- The embedded example labels AQI 101 as `"Unhealthy"` — correct category is `"Unhealthy for Sensitive Groups"`.
- CO2 alert threshold is relaxed at 2,000 ppm — unify on **1,000 ppm**.
- Typos: `fontend-layer.md`, `py-pytourch-pro`, `prosgresql-pro`, `react-typescripts-pro`.

---

## 11. Impact Metrics (For Report)

| Metric | How to Measure | Target |
| :--- | :--- | :--- |
| **CADR (measured)** | Sealed-chamber decay test of the fan + HEPA assembly | Report measured value |
| **Anticipation accuracy** | % of real breaches preceded by a SOFT ALERT ≥ 1h before | > 70% |
| **False alert rate** | % of SOFT ALERTS where no breach actually occurred | < 30% |
| **Intervention efficiency** | Average fan runtime per breach event (minutes) | < 30 min |
| **Occupant exposure reduction** | Cumulative µg·m³·h in controlled space vs uncontrolled baseline | > 40% reduction |
| **Forecast RMSE (q50)** | Root Mean Squared Error of median prediction (15-min resolution) | < 8 µg/m³ |
| **Threshold inference error** | MAE between ML#1 output and physics-model ground truth | < 5 µg/m³ |

---

## 12. Implementation Roadmap (Phased)

> Each phase has deliverables and a definition of done. Milestones: **M1** node reporting · **M2** closed loop running · **M3** intelligent · **M4** measured evidence.

### Group A — Hardware (Phases 0–4)

| Phase | Work | Definition of Done |
| :---: | :--- | :--- |
| **0** Preparation | Inspect parts, identify PSU type, set LM2596 = 5.0V | 5.0V measured; 4 fan wires identified |
| **1** Power + I2C | Buck → ESP32; SCD40 + OLED on I2C 400 kHz; I2C scan | `0x3C + 0x62` found; OLED shows CO2/T/H every 5s |
| **2** Dust sensing | GP2Y1023 → GPIO18, ISR PWM measurement, zero-calibrate W0 → NVS | Stable PM2.5, no garbage readings |
| **3** Fan PWM | Wire 4 pins; LEDC 25 kHz; duty sweep 0→100; kickstart; find `min_duty` | Fan runs smoothly, no stall, no whine |
| **4** Node + MQTT 🏁M1 | Assemble FreeRTOS tasks; Wi-Fi/NTP/MQTT; publish telemetry + state + status/LWT; subscribe command | `mosquitto_pub/sub` confirms all topics |

### Group B — Basic Closed Loop (Phases 5–7)

| Phase | Work | Definition of Done |
| :---: | :--- | :--- |
| **5** Backend ingestion | docker-compose; Flyway V1; MQTT → queue → batch; REST + JWT | Telemetry lands in DB, STOMP broadcast works |
| **6** Space + threshold (rule) | `spaces` table; rule-based `ThresholdService` (stand-in for ML#1); alert pushed to OLED | Changing Space config changes threshold changes alert |
| **7** AUTO closed loop 🏁M2 | `ActuatorService` publishes `set_actuator`; node executes PWM; hysteresis + audit; manual override | Introduce dust ⇒ fan auto-runs ⇒ PM2.5 falls ⇒ auto-stops |

### Group C — Intelligence (Phases 8–9)

| Phase | Work | Definition of Done |
| :---: | :--- | :--- |
| **8** DL forecast | Resample 24h→24h; train on public data; `/forecast`; backend cron + STOMP + OLED | 24-point forecast renders; early warning fires pre-breach |
| **9** ML threshold + dosage 🏁M3 | Physics-model label generation; train GP; `/threshold` + `/dosage` | Two different Spaces yield different thresholds and duties |

### Group D — Polish & Evidence (Phases 10–11)

| Phase | Work | Definition of Done |
| :---: | :--- | :--- |
| **10** Frontend | Space UI, AUTO/MANUAL, decision timeline, forecast chart with both CI bands; fix token/CORS/mockups | All features usable from the UI |
| **11** CADR + evaluation 🏁M4 | Sealed-chamber CADR measurement; DL metrics (RMSE/MAE/MAPE) vs naive baseline | CADR table + model-vs-baseline comparison |

### Phase ↔ Layer Map

| Phase | Layer 1 | Layer 2 | Layer 3 | Layer 4 |
| :---: | :--- | :--- | :--- | :--- |
| 0 | inspect parts | — | — | — |
| 1 | power + I2C | — | — | — |
| 2 | dust sensing | — | — | — |
| 3 | fan PWM | — | — | — |
| 4 | node + MQTT | broker test | — | — |
| 5 | — | ingestion + REST | — | — |
| 6 | receive alert | Space + rule threshold | — | temp config |
| 7 | execute set_actuator | closed loop + audit | — | temp AUTO/MANUAL |
| 8 | OLED forecast | forecast cron | DL forecast | forecast chart |
| 9 | — | call ML | ML#1 + ML#2 | — |
| 10 | — | — | — | full UI |
| 11 | — | — | evaluation | — |

---

## 13. Locked Decisions

| Item | Decision |
| :--- | :--- |
| Concept | Indoor closed loop; **backend is 100% the brain** |
| Threshold | Function of space (volume · purpose · region) |
| Models | 2 ML (threshold, dosage) + 1 DL (24h→24h) |
| Actuators | OLED = soft alert · **1× Intel 4-wire PWM fan** = hard intervention (AUTO + MANUAL) |
| Node | **Single integrated ESP32-S3** per room (sensing + display + fan) |
| **CO2** | **Alert only** — never in the automatic actuation path (HEPA cannot remove CO2) |
| Filtration | DIY: HEPA H13 media (~79k) + Intel fan, PWM on the fan's PWM pin |
| Scale | One small room per fan; larger rooms require a fan bank |

**Open items:** label source for ML#1/#2 (physics/standard-informed proposed) · fan P-Q curve selection if scaling up · CO2 threshold standardised at 1,000 ppm.

---

## 14. Files to Update (Execution Checklist)

| # | File | Action |
| :--- | :--- | :--- |
| 1 | `docs/aqss-concept-v2.md` | ✅ **THIS DOCUMENT** |
| 2 | `docs/io_contract.md` | Add Space, Actuator, Decision, ML/DL contracts; fix §10 issues |
| 3 | `docs/backend-layer.md` | Add Space, Actuation, ControlDecisionService, V4 migration; fix §10.2 |
| 4 | `docs/dl.md` | Replace 2 models with 3 (ML#1, ML#2, DL); remove Autoencoder; fix CI |
| 5 | `docs/embedded-layer.md` | Integrated node, fan PWM, LWT, OLED warning, `space_id`; fix §10.1 |
| 6 | `docs/fontend-layer.md` | Add Space Management, Control View, NowCast AQI, decision timeline; fix §10.4 |
| 7 | `README.md` + `Agents.md` | Reposition: "Supervision" → "Closed-Loop Indoor Control"; fix dead links |

---

## 15. Out of Scope (This Iteration)

- Node separation (sensor node vs actuator node) — V2 uses one integrated node per room.
- Feedback instrumentation (current sense INA219, tachometer RPM read-back) — reserved for a later phase.
- Production deployment (Kubernetes, CI/CD pipeline).
- Mobile app or push notification service.
- Multi-space aggregation and building-level dashboards.
- Implementation code — this document covers architecture and contracts only.
