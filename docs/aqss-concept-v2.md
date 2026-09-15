# AQSS V2 — Closed-Loop Indoor Air Quality Control System

> **Version:** 2.0 — Concept & Architecture Document
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
| **Actions** | None (display only) | Soft alert (OLED) + hard intervention (air filter relay) |
| **ML/DL role** | Decorative (thresholds are hardcoded, DL adds no value beyond `if/else`) | Essential and irreplaceable: threshold inference, dosage calculation, early warning |
| **AQI usage** | Primary metric — computed from instantaneous PM2.5 using EPA 24h breakpoints (incorrect for real-time) | Display-only indicator computed via NowCast; control logic uses raw PM2.5 + space-derived thresholds |

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
                 │ (OLED warn)  │  │ (Relay → filter)  │
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

    hours_to_breach ← first index i where forecast_24h[i].pm25 > threshold

    IF current_pm25 >= threshold:
        → ACTIVATE filter at dosage.level for dosage.duration_minutes
        → mode = AUTO
        → publish aqss/devices/{actuator_id}/command {set_actuator: ON, level: dosage.level}

    ELSE IF hours_to_breach <= 2:
        → WARN on OLED: "Air quality declining. Forecast breach in {hours_to_breach}h."
        → publish aqss/devices/{sensor_id}/command {display_warning: "..."}
        → mode = ALERT (awaiting manual confirmation or auto-escalation)

    ELSE:
        → IDLE
        → IF filter currently running AND current_pm25 < threshold × 0.7:
            → DEACTIVATE filter (hysteresis: 70% of threshold to prevent relay chatter)
```

### 4.2 Safety Constraints

| Constraint | Value | Rationale |
| :--- | :--- | :--- |
| Minimum ON time | 5 minutes | Prevent relay chatter damage |
| Minimum OFF time | 3 minutes | Allow motor cooldown |
| Maximum continuous ON | 120 minutes | Prevent filter overheating |
| Hysteresis band | 70% of threshold | Prevent oscillation at boundary |
| Manual override priority | Always | User can always override AUTO |
| Fail-safe on network loss | Filter OFF after 10 min | No runaway operation without backend brain |
| Audit log | Every decision | Full traceability for debugging |

---

## 5. The Three Models: Inputs, Outputs, and Training

### 5.1 ML#1 — Threshold Inference (Space → Base PM2.5 Threshold)

| Property | Value |
| :--- | :--- |
| **Problem type** | Tabular regression |
| **Algorithm** | Gaussian Process Regression (GPR) or Gradient Boosting (XGBoost) |
| **Input features** | `volume_m3`, `purpose_encoded`, `region_encoded`, `ventilation_estimate`, `occupancy_typical` |
| **Output** | `base_threshold_pm25` (µg/m³) — the PM2.5 concentration at which intervention should begin |
| **Uncertainty** | GPR natively produces `µ ± σ` → gives principled confidence interval (unlike V1's Linear+ReLU fake CI) |

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
| **Output** | 24 hours ahead = 96 time steps × `[pm25_mean, pm25_q10, pm25_q90]` |
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

## 6. Architecture Changes by Layer

### 6.1 New First-Class Entity: Space

A **Space** is the fundamental unit of V2. All thresholds, dosages, and control decisions are scoped to a Space — never to a raw device.

```
Space (phòng trọ "201")
├── Sensor Node: AQSS_ESP32S3_01 (PM2.5, CO2, Temp, Hum)
├── Actuator Node: AQSS_ESP32S3_ACT_01 (Relay → air filter)
├── Derived Threshold: 25 µg/m³ (from ML#1)
├── Dosage Profile: Level 2 for 30 min (from ML#2)
└── Control Decisions: [2026-09-16T10:00 AUTO ON, 2026-09-16T10:32 AUTO OFF, ...]
```

### 6.2 Layer 1 — Embedded (Sensor Node + Actuator Node)

**Sensor Node changes:**
- Add `space_id` field to MQTT telemetry payload.
- OLED: add a new screen showing early-warning messages received via `aqss/devices/{id}/command` with `display_warning` command.
- Publish Last Will and Testament (LWT) to `aqss/devices/{id}/status` with payload `{"status": "OFFLINE"}` so backend detects disconnection.

**New: Actuator Node firmware:**
- Reuses ESP32-S3 hardware (same DevKit).
- Subscribes to `aqss/devices/{id}/command` for `set_actuator` commands.
- Controls a relay module (GPIO output) connected to the air filter power line.
- Publishes actuator state to `aqss/devices/{id}/actuator/state` every 5 seconds.
- Physical manual override button (GPIO input) — always takes priority.
- Fail-safe: if no command received for 10 minutes, turn OFF relay.
- Watchdog timer: 30 seconds, auto-reboot on hang.

### 6.3 Layer 2 — Backend (The Brain)

**New database tables (`V4__spaces_and_actuation.sql`):**

| Table | Purpose |
| :--- | :--- |
| `spaces` | Space registry (id, name, volume_m3, purpose, region, created_at) |
| `space_devices` | Many-to-many: which sensor/actuator nodes belong to which space |
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
| `aqi_level VARCHAR(30)` | Changed to `VARCHAR(40)` — "Unhealthy for Sensitive Groups" is 35 chars |

### 6.4 Layer 3 — AI Service (3 Models)

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

### 6.5 Layer 4 — Frontend (New Screens)

**New page: Space Management (`/spaces`)**
- CRUD interface for creating/editing spaces (name, volume, purpose, region).
- Assign sensor and actuator nodes to spaces via drag-and-drop or dropdown.
- Display ML#1-derived threshold and ML#2-derived dosage profile.
- Visual indicator: threshold confidence interval (from GP uncertainty).

**New page: Space Control View (`/spaces/:id`)**
- Real-time PM2.5 vs threshold line (threshold shown as horizontal reference line with uncertainty band).
- 24h forecast overlay with q10/q50/q90 shaded bands.
- Actuator control panel: AUTO / MANUAL toggle, current state indicator (ON/OFF/level), manual level slider.
- Decision timeline: chronological list of all `ControlDecision` events with expandable detail (trigger, threshold used, dosage prescribed).

**Modified: Dashboard (`/`)**
- Space selector dropdown (replacing device selector).
- AQI gauge now shows NowCast-calculated AQI (display-only).
- New "Early Warning" banner: appears when DL forecast predicts breach within 2 hours.

---

## 7. Bug Fixes & Contract Corrections from V1 Audit

During the V2 architecture review, the following inconsistencies in V1 were discovered and must be corrected regardless of V2 adoption:

### 7.1 AQI Calculation Error

**Problem:** V1 computes `aqi_index` from *instantaneous* PM2.5 readings using EPA breakpoints that are defined for *24-hour average* PM2.5. This produces wildly fluctuating AQI values that don't match what citizens see on government monitoring sites.

**Fix:** Compute AQI using the **NowCast algorithm** (EPA 2013), which applies a weighted average of the last 12 hours of readings. The firmware sends raw PM2.5; the backend computes NowCast AQI.

In V2, AQI becomes a **display-only metric**. All control logic uses raw PM2.5 + space-derived thresholds.

### 7.2 Missing LWT (Last Will and Testament)

**Problem:** Backend subscribes to `aqss/devices/{id}/status` but this topic is never published by the firmware, and the MQTT contract doesn't define it.

**Fix:** Firmware sets LWT on `mqttClient.connect()`:
```cpp
mqttClient.connect(clientId, user, pass,
    "aqss/devices/AQSS_ESP32S3_01/status",  // will topic
    1,                                        // will QoS
    true,                                     // will retain
    "{\"status\":\"OFFLINE\"}"                // will payload
);
```
On successful connect, publish `{"status":"ONLINE"}` with retain=true.

### 7.3 Redis Not Used

**Problem:** `docker-compose.yml` includes a Redis service, but no Spring Boot service references it.

**Fix:** Remove Redis from docker-compose. If caching is needed in the future, use Spring Cache with Caffeine (in-process).

### 7.4 MQTT Auth Undefined

**Problem:** `mosquitto.conf` configuration for authentication is not specified in any plan document.

**Fix:** Add to V4 migration documentation:
```
# mosquitto.conf
listener 1883
allow_anonymous false
password_file /mosquitto/config/pwfile
```

### 7.5 `aqi_level` Column Too Short

**Problem:** `aqi_level VARCHAR(30)` is exactly 30 characters, but "Unhealthy for Sensitive Groups" is 35 characters.

**Fix:** Change to `VARCHAR(40)` in V4 migration.

---

## 8. Updated MQTT Topics (Complete Registry)

| Topic | Direction | QoS | Retain | Payload | V1? |
| :--- | :---: | :---: | :---: | :--- | :---: |
| `aqss/devices/{id}/telemetry` | Device → Server | 1 | No | Sensor JSON (+ `space_id` in V2) | ✅ |
| `aqss/devices/{id}/status` | Device → Server | 1 | **Yes** | `{"status": "ONLINE/OFFLINE"}` | ❌ NEW |
| `aqss/devices/{id}/command` | Server → Device | 1 | No | `{command, params}` (+ `set_actuator`, `display_warning` in V2) | ✅ |
| `aqss/devices/{id}/actuator/state` | Actuator → Server | 1 | No | `{"state": "ON/OFF", "level": 2, "mode": "AUTO/MANUAL"}` | ❌ NEW |

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
| Filter CADR | 150 m³/h (typical HEPA portable) |

### Step 2: ML#1 Threshold Inference

Input: `{volume: 15, purpose: "bedroom", region: "hanoi", ventilation: "low", occupancy: 1}`

Using the mass-balance surrogate:
- Penetration factor P = 0.8 (typical Vietnamese apartment, gaps in windows)
- Indoor source S ≈ 0 (non-smoker, no cooking)
- Natural ventilation λ_nat ≈ 0.3 h⁻¹
- Steady-state indoor PM2.5 ≈ C_outdoor × P = 35 × 0.8 = **28 µg/m³**

WHO 24h guideline = 15 µg/m³. For 8h sleeping exposure, we want cumulative dose < WHO target:
- Target C_indoor = 15 µg/m³ × (24/8) correction... but we use the direct guideline for simplicity.

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
- Push `display_warning` command to sensor node OLED: "⚠ Air quality may decline by 01:00. Consider turning on filter."
- STOMP push to frontend `/topic/spaces/201/decision`: `{action: "ALERT", reason: "forecast_breach_q90", hours_to_breach: 1.75}`
- User sees warning on dashboard and OLED. They can manually activate the filter or wait.

At **00:15**, real PM2.5 has risen to **19 µg/m³** (above threshold 18):

**Decision: AUTO ACTIVATE.**
- ML#2 Dosage: `{filter_level: 2 (medium), run_duration: 25 min}` (for 15 m³ room at 19 µg/m³ with CADR 150 on medium)
- Publish to actuator: `{command: "set_actuator", params: {state: "ON", level: 2}}`
- Log to `control_decisions`: `{space_id: 201, action: "ACTIVATE", mode: "AUTO", threshold: 18, dosage: {level: 2, duration: 25}, trigger: "threshold_breach"}`

At **00:40**, PM2.5 has dropped to **11 µg/m³** (below threshold × 0.7 = 12.6):

**Decision: AUTO DEACTIVATE.**
- Publish: `{command: "set_actuator", params: {state: "OFF"}}`
- Log decision.

The occupant slept through the entire episode. The system handled it autonomously.

---

## 10. Impact Metrics (For Report)

| Metric | How to Measure | Target |
| :--- | :--- | :--- |
| **Anticipation accuracy** | % of real breaches preceded by a SOFT ALERT ≥ 1h before | > 70% |
| **False alert rate** | % of SOFT ALERTS where no breach actually occurred | < 30% |
| **Intervention efficiency** | Average filter runtime per breach event (minutes) | < 30 min |
| **Occupant exposure reduction** | Cumulative µg·m³·h in controlled space vs uncontrolled baseline | > 40% reduction |
| **Forecast RMSE (q50)** | Root Mean Squared Error of median prediction (15-min resolution) | < 8 µg/m³ |
| **Threshold inference error** | MAE between ML#1 output and physics-model ground truth | < 5 µg/m³ |

---

## 11. Files to Update (Execution Checklist)

| # | File | Action |
| :--- | :--- | :--- |
| 1 | `docs/aqss-concept-v2.md` | ✅ **THIS DOCUMENT** |
| 2 | `docs/io_contract.md` | Add Space, Actuator, Decision, ML/DL contracts; fix §7.1–7.5 |
| 3 | `docs/backend-layer.md` | Add Space, Actuation, ControlDecisionService, V4 migration |
| 4 | `docs/dl.md` | Replace 2 models with 3 (ML#1, ML#2, DL); remove Autoencoder; fix CI |
| 5 | `docs/embedded-layer.md` | Add actuator node firmware, LWT, OLED warning, `space_id` |
| 6 | `docs/fontend-layer.md` | Add Space Management, Control View, NowCast AQI, decision timeline |
| 7 | `README.md` + `Agents.md` | Reposition: "Supervision" → "Closed-Loop Indoor Control" |

---

## 12. Out of Scope (This Iteration)

- Physical demo chamber construction and sensor calibration procedure.
- Production deployment (Kubernetes, CI/CD pipeline).
- Mobile app or notification service (push notifications).
- Multi-space aggregation and building-level dashboards.
- Implementation code — this document covers architecture and contracts only.
