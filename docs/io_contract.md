# AQSS V6 — I/O Contract

> **Single source of truth for the data contracts between the layers.**
> **Version 6.3** — adds the 360-step forecast path (`forecast_paths`) and the incremental worker
> read. Measurement, control, MQTT and the previous tables are unchanged.

## What changed from 6.2

| Added | Why |
| :--- | :--- |
| `forecast_paths` table + `path` on `POST /internal/v1/ai/forecast-runs` | One run is a curve, not three points. 360 floats in one `REAL[]` instead of 360 rows |
| `path` in `GET /devices/{id}/forecast` and in the `forecast` SSE event | The chart draws the whole future line without a second request |
| `GET /internal/v1/ai/devices/{id}/samples?after_id` and `.../actuator-reports?after_id` | The worker keeps a context cache instead of reloading 60 minutes per packet |
| `GET /api/v1/devices/{id}/forecast/path` | Read one stored curve by run id, for the drawer and exports |

## What changed from 6.1

| Added | Why |
| :--- | :--- |
| Forecast evaluator | `forecast_points` already carries `actual_pm25`, `error` and `evaluation_status`; nothing was filling them. Scored prospectively, never after the fact |
| `GET /api/v1/devices/{id}/forecast/accuracy` | The three indicators of the room panel and its drawer |
| `metrics` SSE event | The panel updates when a target is scored, not on a poll |

## What changed from 6.0

| Added | Why |
| :--- | :--- |
| `GET /api/v1/devices/{id}/live/snapshot` | One initial payload for the room chart: raw 30-minute window, latest forecast, cursor |
| `GET /api/v1/devices/{id}/live/events` | `text/event-stream`: every stored sample reaches the chart without polling |
| `forecast` and `actuator` events | The UI replaces the future line and the reported fan state without a page refresh |

## What changed from 5.0

| Added | Why |
| :--- | :--- |
| `telemetry.received_at`, `time_quality`, `quality_flags`, `dust_fault`, `uptime_sec`, `free_heap`, `min_free_heap`, `payload_hash` | A sample needs to say when it was measured, when it was received, and whether it is usable. Legacy rows keep `time_quality = legacy` and no invented receive time |
| `actuator_history` | The node's reports are evidence; the device row is only the latest mirror |
| `command_log` | Intent and observation are different facts. `REPORT_MATCHED` means a report agreed with a request, not an ACK |
| `space_profile_versions` | A forecast must be replayable against the profile that produced it |
| `room_events`, `experiments` | Operator context, never required by a model |
| AI tables (`ai_workers`, `ai_jobs`, `ai_dataset_snapshots`, `ai_models`, `forecast_runs`, `forecast_points`, `ai_alerts`) | Worker queue, immutable dataset membership, model registry and a prospective forecast ledger |
| `/internal/v1/ai/**` | Outbound-only worker API. Bearer token; the browser never calls it |
| `/api/v1/devices/{id}/series|summary|samples` | SQL bucketing with median/min/max/coverage instead of silently dropping spikes |
| `/api/v1/ai/**`, `/api/v1/devices/{id}/forecast|alerts|commands|actuator-history` | Read side for the desktop screens |
| `/api/v1/devices/{id}/events`, `/api/v1/experiments` | Recording what the room was doing |

The MQTT topics, payloads and firmware commands are **unchanged**. The AI worker is optional:
`AI_ENABLED=false` closes `/internal/**` and removes nothing else.

Everything else from 5.0 stands.

## What changed from 4.0

| Removed | Why |
| :--- | :--- |
| Layer 3 (FastAPI: threshold / dosage / forecast), `AI_ENABLED`, `AI_BASE_URL` | The threshold is now computed **in-process** from the room profile and live CO₂; the fan level on a breach is fixed. A separate service added deployment cost and returned numbers a formula can produce |
| `predictions` table, `GET/POST /devices/{id}/forecast` | Nothing produced or read a forecast. At 5 s cadence a room's PM2.5 becomes unpredictable beyond ~30 minutes, so a 24 h model had nothing to learn from |
| `CONTROL_FORECAST_*`, `CONTROL_LEASE_RENEW_MINUTES` is kept | Dead configuration is worse than none |

| Changed | Why |
| :--- | :--- |
| Fan activation is **immediate** (no minimum OFF wait) | Waiting 3 minutes while the air is already bad is a delay, not protection. The motor is protected on the OFF side: minimum ON, maximum ON |
| `threshold_source` gains `profile` | A threshold derived from the room, distinct from one typed by an operator |
| `devices.filter_runtime_s` / `filter_replaced_at` | The node always counted fan hours and published them; the backend was discarding the value |
| `devices.last_reason` / `last_decision_at` | The dashboard needs to say *why* the fan is running, not just that it is |
| `POST /devices/pair` takes `space_name` | The operator names a room; the code is derived |

Everything else from 4.0 stands.

---

## 1. MQTT

Broker: any MQTT v3.1.1 broker reachable over the internet (EMQX Cloud, HiveMQ, mosquitto).
TLS brokers use port 8883; the firmware pins DigiCert Global Root G2 and enables TLS with
`MQTT_USE_TLS=1`.

| Topic | Direction | QoS | Retain | Payload |
| :--- | :--- | :---: | :---: | :--- |
| `aqss/devices/{id}/telemetry` | device → server | 1 | no | §2, every 5 s (`set_interval`) |
| `aqss/devices/{id}/actuator/state` | device → server | 1 | no | §3, on any change + every 5 s while ON |
| `aqss/devices/{id}/status` | device → server | 1 | **yes** | §4, `ONLINE` on connect, `OFFLINE` via LWT |
| `aqss/devices/{id}/command` | server → device | 1 | no | §5 |

The broker replays the retained `status` on every backend connect. That is expected: `status` is
used to learn the device exists, its pairing code, and liveness — never to bind a room.

## 2. Telemetry payload

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "space_id": "SP_201",
  "pairing_code": "X35AJK",
  "timestamp": 1762740000,
  "sensors": { "pm25": 35.4, "co2": 620.0, "temperature": 29.5, "humidity": 68.2 },
  "system":  { "wifi_rssi": -65, "uptime_sec": 3600, "free_heap": 245760,
               "min_free_heap": 185000, "pm25_saturated": false, "dust_fault": false }
}
```

| Field | Unit | Notes |
| :--- | :---: | :--- |
| `device_id` | — | ≤ 32 chars. A row is created on first sight, **unpaired** |
| `space_id` | — | The node's own NVS copy. **Advisory only** — it never binds a room; pairing does |
| `pairing_code` | — | 6 symbols from `A–Z2–9` (no `I/O/0/1`), derived from the chip MAC. Shown on the OLED |
| `timestamp` | s (epoch) | NTP; the server falls back to its own clock if `< 1.7e9` |
| `sensors.pm25` | µg/m³ | GP2Y1023 `(W − W₀) / 14`; `null` until the first 1 s sample window |
| `sensors.co2` / `temperature` / `humidity` | ppm / °C / % | SCD40; `null` until its first periodic reading |
| `system.pm25_saturated` | — | The pulse width reached `DUST_SATURATION_US`; the value is a floor |
| `system.dust_fault` | — | No valid pulse for 3 s — sensor unplugged or its LED is dead |

**Null is not zero.** A `null` field means "no reading yet"; it is stored as SQL `NULL` and is not a
validation failure. A `0.0` in `co2` would be out of range and would get the whole sample rejected.

## 3. Actuator state payload

```json
{ "device_id": "AQSS_ESP32S3_01", "timestamp": 1762740000, "state": "ON",
  "level": 2, "duty_pct": 60, "mode": "AUTO", "filter_runtime_h": 12.3 }
```

Published when `state`, `level` or `mode` changes, and every 5 s while the fan is ON.
`filter_runtime_h` is the node's own fan-hours counter — the backend stores it (§6).

## 4. Status + LWT

```json
{ "device_id": "AQSS_ESP32S3_01", "pairing_code": "X35AJK",
  "status": "ONLINE", "timestamp": 1762740000 }
```

The firmware registers the will on `aqss/devices/{id}/status` (retain, QoS 1,
`{"status":"OFFLINE"}`). `OFFLINE` never advances `last_seen_at` — only `ONLINE` does, and telemetry
advances it too.

## 5. Commands (server → device)

```json
{ "command": "set_actuator", "params": { "state": "ON", "level": 3, "mode": "AUTO" } }
```

| Command | Params | Effect |
| :--- | :--- | :--- |
| `set_actuator` | `state: ON\|OFF`, `level: 0–3`, `mode: AUTO\|MANUAL` | Duty 0 / 35 / 60 / 90 %. `mode` is applied to the node; it also renews the 10 min fail-safe lease |
| `set_space` | `space_id` | `""` means **forget**: the node drops its NVS copy, clears the paired flag, and the OLED returns to the pairing code |
| `reset_filter` | — | Clears the fan-hours counter in NVS |
| `set_interval` | `interval_ms` | Telemetry period (1000–60000) |
| `recalibrate_dust` | `baseline_w0` (opt.) | GP2Y1023 baseline; defaults to the current pulse |
| `display_warning` | `message`, `severity: INFO\|WARNING\|CRITICAL` | Text band on the OLED (30 min TTL) |
| `force_ntp_sync` | — | Immediate NTP resync |
| `reboot` | — | `ESP.restart()` |

The firmware null-checks `command` before comparing, so a malformed payload cannot crash the node.
A `set_actuator` in `AUTO` mode is a **lease renewal**: the backend republishes it at least every
5 minutes while a fan runs, so the node's fail-safe does not cut a healthy fan.

---

## 6. Database (Flyway `V1` → `V4`)

| Table | Columns | Notes |
| :--- | :--- | :--- |
| `spaces` | `code` PK, `name`, `volume_m3`, `purpose`, `region`, `ventilation_estimate`, `threshold_pm25`, `threshold_source` (`manual`/`profile`), `updated_at` | The threshold lives on the room |
| `devices` | `device_id` PK, `name`, `space_code` FK, `pairing_code`, `paired_at`, `fan_state`, `fan_level`, `fan_mode`, `on_since`, `off_since`, `last_command_at`, `telemetry_interval_ms`, `dust_baseline_us`, `last_seen_at`, `filter_runtime_s`, `filter_replaced_at`, `last_reason`, `last_decision_at`, `created_at` | Created on first message, unpaired |
| `telemetry` | `id`, `device_id` FK, `recorded_at`, `pm25`, `co2`, `temperature`, `humidity`, `wifi_rssi`, `pm25_saturated` | Index `(device_id, recorded_at DESC)` |
| `audit_log` | `id`, `occurred_at`, `actor` (`USER`/`BACKEND`/`DEVICE`/`AI`), `action`, `device_id`, `detail` | One log for every action |

Migrations: `V1` schema, `V2` pairing, `V3` filter runtime + decision reason, `V4` drops
`predictions`.

## 7. REST API

JSON is **snake_case** everywhere (platform-wide Jackson setting). No authentication.

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `GET` | `/api/v1/devices` | **Paired** devices (`?include_unpaired=true` for all) |
| `GET` | `/api/v1/devices/{id}` | One device |
| `PUT` | `/api/v1/devices/{id}` | Patch `{name, space_code, telemetry_interval_ms, dust_baseline_us}`; `null` leaves a field untouched |
| `DELETE` | `/api/v1/devices/{id}` | Forget the device |
| `POST` | `/api/v1/devices/pair` | `{pairing_code, space_name, name?, purpose?, volume_m3?, occupancy_hours?, ventilation_estimate?}` → link a node, creating the room if needed |
| `POST` | `/api/v1/devices/{id}/unpair` | Drop the binding **and** tell the node to forget it |
| `POST` | `/api/v1/devices/{id}/filter` | "New filter fitted" — resets the counter on both sides |
| `POST` | `/api/v1/devices/{id}/command` | Any command from §5 |
| `GET` | `/api/v1/devices/{id}/latest` | Newest reading |
| `GET` | `/api/v1/devices/{id}/telemetry?from&to` | Chart data, default last 24 h, thinned to ~800 points |
| `GET` | `/api/v1/devices/{id}/live/snapshot` | Raw 30-minute window + latest forecast + event cursor (§12) |
| `GET` | `/api/v1/devices/{id}/live/events?after` | SSE stream of `telemetry`, `forecast`, `actuator`, `reset` (§12) |
| `GET` | `/api/v1/spaces` | Rooms + thresholds |
| `GET` | `/api/v1/spaces/{code}` | One room |
| `PUT` | `/api/v1/spaces/{code}` | Patch `{name, volume_m3, purpose, region, ventilation_estimate, occupancy_hours, threshold_pm25}` |
| `DELETE` | `/api/v1/spaces/{code}` | Delete |
| `GET` | `/api/v1/spaces/{code}/threshold/preview` | The threshold the profile implies, **without saving** |
| `POST` | `/api/v1/spaces/{code}/threshold` | Compute and store it (`threshold_source: profile`) |
| `GET` | `/api/v1/audit?device_id&limit` | Audit trail, newest first |
| `GET` | `/actuator/health` | Liveness |

### 7.1 Pairing

```json
{ "pairing_code": "X35AJK", "space_name": "Phòng ngủ", "name": "Máy phòng ngủ",
  "purpose": "bedroom", "volume_m3": 15, "occupancy_hours": 8,
  "ventilation_estimate": "low" }
```

* The code is normalised (uppercase, non-alphanumerics stripped) and matched against
  `devices.pairing_code`.
* When `space_name` is given and no such room exists, one is created: the code is derived from the
  ASCII skeleton of the name (`Phòng ngủ tầng 2` → `PHONG_NGU_TANG_2`), and its threshold is computed
  from the profile in the same request.
* `404` — no node has connected with that code. `400` — invalid code. `409` — already paired elsewhere.

Room presets must send volume, occupancy hours and ventilation as well as purpose. Pairing accepts
`ventilation_estimate` (`low`, `medium`, `high`). Existing rooms can patch these fields, including
`occupancy_hours` (integer 1–24), through `PUT /spaces/{code}`. Volume must be finite and positive.
Preset values are editable assumptions, not measured room dimensions.

### Forecast pipeline revision: feature-v2

Minute buckets are labelled by their exclusive end (samples in `[t-1m,t)`), and inference only
uses complete buckets whose samples were received before issue time. Low-coverage PM buckets
are missing features/labels, not valid measurements. Learned models from feature-v1 must be
retrained. Training labels may not cross a validation or test boundary; production promotion
uses validation only, with the frozen test reported independently.
The training data gate counts valid PM minutes (minimum 19.2 hours), not elapsed calendar span.

Forecast `data_quality` also carries `model_kind` (`learned` or `baseline`), `pm_at_origin`,
`fallback_reason` when applicable, and `recommendations`: a list of `{kind, severity, title,
detail}`. These are rule-based advisories from measured conditions and forecast, never actuator
commands or calibrated probabilities. A persistence forecast needs a recent valid complete
minute, not 60 minutes of lag history. Worker capabilities may include per-device inference
status; `/api/v1/ai/status` exposes these capabilities in each worker entry.

### 7.2 Threshold computation

There is no model. `ThresholdService` applies a dose argument over the room's mass balance:

```
threshold = 15 × √(24 / occupancy_hours) × f(volume) × f(ventilation) × f(CO₂)
```

| Factor | Range | Meaning |
| :--- | :--- | :--- |
| `15` | — | WHO 2021 24 h guideline, µg/m³ |
| `√(24/h)` | 1.0 – 3.5 | A room occupied longer tolerates a lower peak |
| `f(volume)` | 0.7 – 1.6 | `∛(V/20)`; a larger room dilutes more |
| `f(ventilation)` | 0.75 – 1.35 | low / medium / high |
| `f(CO₂)` | 0.75 – 1.0 | CO₂ above 420 ppm means a sealed, occupied room — exactly when a lower bar is right |

The result is clamped to `[10, 60]`. The upper bound is a health rule, not a formula output: above
60 µg/m³ the air is unhealthy for sensitive groups on every index the project quotes.
The response reports every factor, so the dashboard explains the number instead of asserting it.

## 8. Sensor acceptance window (firmware and backend enforce the same table)

| Sensor | Unit | Valid | Reject |
| :--- | :---: | :--- | :--- |
| PM2.5 | µg/m³ | 0.0 – 500.0 | outside |
| CO2 | ppm | 400 – 5000 | outside |
| Temperature | °C | −10 – 60 | outside |
| Humidity | % | 0 – 100 | outside |
| RSSI | dBm | −100 – 0 | — |

A `null` sensor field is accepted and stored as `NULL`. The firmware drops an out-of-range sample
before it reaches the payload; the backend is the second gate.

## 9. Control loop

Telemetry arrives every 5 s and **evaluates immediately**; a 60 s timer re-evaluates as a safety net
for devices that stopped reporting. Only devices with `paired_at`, `fan_mode = AUTO` and a
`space_code` participate. A reading older than 5 minutes is ignored.

```
if fan is OFF and pm25 >= threshold                 -> ACTIVATE, level 3, reason threshold_breach
if fan is ON and on for >= 120 min                  -> DEACTIVATE, reason max_on
if fan is ON, pm25 < 0.7 × threshold, on >= 5 min   -> DEACTIVATE, reason hysteresis_off
otherwise (AUTO, fan ON)                            -> republish set_actuator every 5 min (lease)
```

**Activation never waits.** A breach means the air is already bad; the motor is protected on the
other side of the cycle — it runs at least 5 minutes once started, and at most 2 hours.

Every activation and deactivation is written to `audit_log` with `actor = BACKEND`, and the device
row records `last_reason` / `last_decision_at` so the dashboard can say why in one sentence.
A manual command records `reason = manual`.

CO₂ never actuates: HEPA media cannot remove it. It is stored, shown, and used only as an input to
the threshold.


## 10. Optional forecast demo service (frontend experiment)

The standalone `forecast-demo/` service reads the existing telemetry GET endpoint. It does not
change the Spring backend, MQTT, firmware, or control decisions. The frontend calls its
`GET /forecast/{device_id}` endpoint (base URL: `VITE_FORECAST_API_URL`, default
`http://localhost:8001`). The service reads 24 hours from the configured `BACKEND_URL`, aggregates
5-minute PM2.5 medians, and fits scikit-learn Ridge regression using six lagged values.

Response: `{status, message, observed: [{at, pm25}], predicted: [{at, pm25}], model,
training_rows, window_hours, validation}`. `at` is Unix **milliseconds**. `predicted` includes
an anchor and six 5-minute forecasts (30 minutes). Status is `ready`, `insufficient_data`, or
`stale_data`; unavailable forecasts have an empty `predicted` list. Null, invalid, future and
saturated readings are excluded. Latest valid input must be within 5 minutes. Missing time buckets
are not filled across gaps. Optional `validation` is a chronological one-step (5-minute) holdout
MAE compared with persistence; it is not a confidence interval or 30-minute accuracy guarantee.
Upstream transport errors return 502; upstream 404 responses are forwarded. See `forecast-demo/README.md`.

---

# 11. V6 data foundation, export and AI service

## 11.1. Ingestion and provenance

| Field | Meaning |
| :--- | :--- |
| `telemetry.recorded_at` | Device timestamp when it is plausible, otherwise the receive time. Drives charts and control |
| `telemetry.received_at` | Backend receive time. **Never** backfilled for legacy rows (they stay `NULL`) |
| `telemetry.time_quality` | `device` \| `server_assigned` \| `legacy` |
| `telemetry.quality_flags` | JSONB. Example `{"pm25_saturated": true}`; flags do not reject the row |
| `telemetry.payload_hash` | SHA-256 of the canonical payload. A duplicate is stored once |

Rules:

- The device id in the MQTT topic and in the payload must agree; a mismatch is dropped.
- A whole sample is still rejected when a present sensor value is outside the §8 window.
- `dust_fault`, `uptime_sec`, `free_heap` and `min_free_heap` are stored, not discarded.
- A saturated or dust-fault sample remains useful diagnostically but is **not** a valid PM target
  or feature in the AI pipeline.
- `devices.last_seen_at` follows the receive clock. A buffered packet delivered late does not move
  liveness backwards, and `devices.last_telemetry_received_at` / `last_actuator_received_at` record
  the two streams separately.

## 11.2. Command lifecycle

```
REQUESTED -> PUBLISHED -> REPORT_MATCHED
     |            |
     |            +-> UNCONFIRMED (15 s window; a later matching report may still match)
     +-> PUBLISH_FAILED
PUBLISHED -> SUPERSEDED (a newer instruction replaced it)
```

- `command_log` is written before the publish. A failed publish does **not** change the reported
  fan state; `last_command_at` moves only after a successful publish.
- A lease renewal is a command too, and `last_command_at` moves only when it is published.
- `REPORT_MATCHED` requires an actuator report whose state/level/mode agree with the request within
  the 15 s window. It is not an ACK carrying a command id and says nothing about RPM.
- `actuator_history` is append-only and is the source of truth for reported state.
- While an AUTO command is pending, the control loop does not issue another one. Manual commands
  are never replayed automatically.
- `max_on` is checked before the stale-reading guard: a fan that has run its maximum stops even
  when PM is stale.

## 11.3. AI tables

| Table | Key columns |
| :--- | :--- |
| `ai_workers` | `id`, `last_heartbeat_at`, `capabilities`, `model_artifact`, `status` |
| `ai_jobs` | `id` UUID, `type` (train/evaluate/export), `device_id`, `status`, lease token/expiry, progress, result/error |
| `ai_dataset_snapshots` | `id`, `device_id`, `from_at/to_at`, ordered id `manifest`, `context_manifest`, `counts`, `snapshot_hash`, `expires_at` (7 days) |
| `ai_models` | `id`, `version`, `algorithm`, `feature_version`, `dataset_snapshot_id`, `metrics`, `artifact_hash/path`, `status` |
| `forecast_runs` | `id`, `device_id`, `model_version`, `input_cutoff`, `issued_at`, `accepted_at`, `threshold_pm25`, `profile_version`, `data_quality` |
| `forecast_points` | `run_id`, `horizon_minutes`, `target_at`, `pm25`, `actual_pm25`, `error`, `evaluation_status` |
| `ai_alerts` | `id`, `device_id`, `forecast_run_id`, `type`, `threshold_snapshot`, `lifecycle`, `feedback` |

A forecast is prospective only when the backend's `accepted_at` is before the target. A point whose
target already passed is stored as `LATE_SUBMISSION` and is never scored as a forecast.

## 11.4. Internal API (worker only)

All under `/internal/v1/ai`, `Authorization: Bearer $AI_WORKER_TOKEN` when the token is set.

| Endpoint | Purpose |
| :--- | :--- |
| `POST /dataset-snapshots` | Freeze ordered membership for a window (`device_id`, `from`, `to`) |
| `GET /devices/{id}/telemetry?snapshot_id&cursor&limit` | Stable paginated telemetry (default 2000, max 5000) |
| `GET /devices/{id}/actuator-history?snapshot_id&cursor` | Same for actuator reports |
| `GET /devices/{id}/events?snapshot_id&cursor` | Same for room events |
| `GET /devices/{id}/context` | Device + room profile + profile versions |
| `GET /devices/{id}/recent?minutes=90` | Bounded recent raw rows for inference |
| `GET /devices/{id}/recent-actuator?minutes=90` | Bounded recent actuator reports |
| `POST /jobs/claim?worker_id` | Atomically claim one queued job (204 when none) |
| `POST /jobs/{id}/progress?lease_token` | Progress + lease extension |
| `POST /jobs/{id}/complete?lease_token` | Idempotent completion |
| `POST /models` | Register model metadata |
| `POST /forecast-runs` | Upload one run and its points in one transaction |
| `POST /alerts` | Create an alert (one ACTIVE per device; idempotent) |
| `POST /alerts/resolve?device_id` | Resolve the active alerts for a device |
| `POST /workers/heartbeat` | Worker liveness and artifact availability |

## 11.5. Public AI reads

| Endpoint | Purpose |
| :--- | :--- |
| `GET /api/v1/ai/status?device_id` | Worker online/offline, current model, latest data/forecast times |
| `POST /api/v1/ai/jobs` | Queue train/evaluate/export (202) |
| `GET /api/v1/ai/jobs`, `/ai/jobs/{id}` | Progress and result |
| `GET /api/v1/ai/models?device_id` | Model versions, metrics, status |
| `GET /api/v1/devices/{id}/forecast` | Latest run and points, with timestamps |
| `GET /api/v1/devices/{id}/forecast/accuracy?horizon&hours&model_version` | MAE and within-tolerance rate for the cohort (§13) |
| `GET /api/v1/devices/{id}/forecast/accuracy/points?horizon&hours&limit` | Drawer rows behind those indicators (§13) |
| `GET /api/v1/devices/{id}/alerts` | Alert episodes |
| `POST /api/v1/alerts/{id}/feedback` | Record operator feedback (accepted / ignored / handled) |
| `GET /api/v1/devices/{id}/commands` | Command lifecycle |
| `GET /api/v1/devices/{id}/actuator-history` | Reported state history |

## 11.6. Chart and analysis reads

| Endpoint | Response |
| :--- | :--- |
| `GET /api/v1/devices/{id}/series?from&to&bucket=1m\|5m\|15m` | Per bucket: `bucket_start`, median, min, max, count, expected_count, coverage |
| `GET /api/v1/devices/{id}/summary?from&to&threshold` | avg/median/max, coverage, minutes above threshold, unknown minutes |
| `GET /api/v1/devices/{id}/samples?from&to&limit&cursor` | Raw rows, cursor-paginated |

`series` never drops a spike to fit a point budget: the bucket gets larger or the request is
rejected above 2,000 buckets.

## 11.7. Configuration

| Variable | Used by |
| :--- | :--- |
| `AI_ENABLED` | Backend: closes `/internal/**` when false |
| `AI_WORKER_TOKEN` | Backend + worker bearer token |
| `BACKEND_URL`, `WORKER_ID` | Worker |
| `ARTIFACT_DIR`, `SNAPSHOT_DIR` | Worker local storage |
| `POLL_SECONDS=10`, `INFERENCE_SECONDS=60`, `HEARTBEAT_SECONDS=15` | Worker cadence |
| `TRAIN_INTERVAL_HOURS=6`, `MAX_HISTORY_DAYS=14` | Retraining policy |

The worker is outbound-only; the backend never dials the laptop. `forecast-demo/` remains an
optional reference until the new service reaches acceptance.

---

# 12. Live room stream (V6.1)

The room screen keeps one chart: measured history on the left, forecast on the right, one X axis.
The chart is fed by a snapshot plus an SSE stream; there is no 5-second polling of a bucketed
endpoint behind it.

## 12.1 Snapshot

`GET /api/v1/devices/{id}/live/snapshot`

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "server_time": "2026-11-14T09:00:00Z",
  "cursor": 142,
  "window_seconds": 1800,
  "samples": [ "...telemetry rows, oldest first..." ],
  "forecast": { "run": { "...": "..." }, "points": [ "...": "..." ] },
  "device": { "...device row..." }
}
```

| Field | Notes |
| :--- | :--- |
| `samples` | Raw rows in `[anchor - 30 min, anchor]`, where `anchor` is the newest valid `recorded_at`. No bucket, no interpolation, null PM kept |
| `forecast` | Latest run + its points (5/15/30 min), or `null`. The chart draws the anchor and these target times only |
| `cursor` | High-water event id, captured **before** the sample query. Anything published between the two steps is replayed by the stream, and the client deduplicates by sample id |
| `server_time` | Clock-skew display only; event timestamps stay authoritative |

## 12.2 Event stream

`GET /api/v1/devices/{id}/live/events?after={cursor}` with `Accept: text/event-stream`.
`Last-Event-ID` wins over `after` when both are present (the browser sends it on reconnect).

```
id: 143
event: telemetry
data: {"event_id":143,"device_id":"AQSS_ESP32S3_01","type":"telemetry",
       "occurred_at":"2026-11-14T09:00:05Z","received_at":"2026-11-14T09:00:05.120Z",
       "payload":{ "...one telemetry row..." }}
```

| Event | Payload | When |
| :--- | :--- | :--- |
| `telemetry` | The stored telemetry row (`id`, `recorded_at`, `received_at`, sensors, flags) | After the ingestion transaction commits |
| `forecast` | `{run, points}` (same shape as `GET /devices/{id}/forecast`) | After a new run is stored; duplicate uploads do not emit again |
| `actuator` | `{state, level, duty_pct, mode, filter_runtime_s}` | After the actuator report commits |
| `metrics` | `{device_id, horizon_minutes, actual_pm25, error}` | After a point is scored, or declared missing, by the evaluator |
| `reset` | `{device_id, cursor, server_time}` | Cursor is older than the ring buffer, or the server restarted |
| comment | `: heartbeat` | Every 15 s; keeps proxies and the browser from closing an idle stream |

Rules:

- Every event carries the full envelope; `event_id` is a process-local monotonic cursor per device.
  It is also the SSE `id:`. A server restart resets it, which is why a client that sees a cursor
  ahead of the server must resync.
- The buffer keeps the last 1,000 events per device. A slow client has a bounded queue; when it
  overflows it receives `reset` instead of blocking ingestion.
- Events are published **after transaction commit**. `SseEmitter.send` never runs on the MQTT
  callback thread.
- Response headers disable caching and proxy buffering (`Cache-Control: no-cache`,
  `X-Accel-Buffering: no`). The emitter times out after 30 minutes; `EventSource` reconnects with
  its last id.
- Unknown event names are ignored, so the stream can add more events without a breaking change.

## 12.3 Client sequence

1. `GET /live/snapshot`; render the measured window and the latest forecast, remember `cursor`.
2. Open `EventSource` on `/live/events?after={cursor}`.
3. Deduplicate by `event_id` and by sample/run id; apply `telemetry` in `recorded_at` order and
   replace the future line only when the incoming run is newer than the one on screen.
4. On `reset` or a stream error: keep the chart visible, request a new snapshot, then reopen the
   stream. Polling is a fallback only while the stream is unavailable; it is stopped once SSE is
   live.
5. The chart domain is `[anchor - 30 min, anchor + 30 min]`; the anchor moves with every new
   sample. A missing sample leaves a gap; it is never filled with a synthetic point.

## 12.4 What is not in this version

`advisory` events and the full 360-step forecast path are still reserved. Full paths arrive with the
Chronos-backed worker; until then the forecast line uses the three target points stored in
`forecast_points`.

---

# 13. Forecast evaluation (V6.2)

`forecast_points.actual_pm25`, `error` and `evaluation_status` existed from V6 but nothing filled
them. This section defines who does, and what the panel may claim.

## 13.1 Scoring rules

| Rule | Detail |
| :--- | :--- |
| Prospective only | A point is scored only when the backend's `accepted_at` is before `target_at`. A run uploaded later keeps its stored points but is never counted as a forecast |
| Sample matching | The nearest **usable** sample within **±3 s** of the target; a distance tie takes the earlier timestamp |
| Usable sample | `pm25` not null, `pm25_saturated = false`, `dust_fault` not true. A saturated reading is a floor and a fault is a broken sensor; neither is a measurement of the room, and neither may score a forecast |
| No interpolation | A missing sample is never bridged from the neighbours; the target is either matched or not |
| One sample per target | A single observation cannot score two targets of the same run |
| Late data | A point stays `PENDING` for **10 minutes** after its target, then becomes `UNSCORED_MISSING_TARGET` |
| Missing is not zero | An unmatched target keeps `actual_pm25 = error = NULL`. `error = 0` would read as a perfect forecast |
| Cadence | Due points are scored by a poll every `AI_EVALUATION_INTERVAL_MS` (default 15 s), batched 500 at a time |

Statuses: `PENDING` → `SCORED` | `UNSCORED_MISSING_TARGET`; `LATE_SUBMISSION` is written at upload
time and never scored.

## 13.2 Accuracy read

`GET /api/v1/devices/{id}/forecast/accuracy?horizon=5|15|30&hours=6&model_version=...`

| Field | Meaning |
| :--- | :--- |
| `matched` | Scored pairs used as the denominator of both indicators |
| `mae_ug_m3` | Mean of `abs(predicted − actual)` over `matched`; `null` when nothing is matched |
| `within_tolerance_pct` | Percentage of matched pairs with `abs(error) ≤ tolerance_ug_m3`; `null` when nothing is matched |
| `tolerance_ug_m3` | 5.0; always shown next to the percentage |
| `pending`, `missing`, `late` | The rest of the cohort — they explain why the denominator is small |
| `horizons[]` | The same numbers for 5/15/30 minutes, so switching the selector needs no request |
| `model_version` | The cohort. Defaults to the model version of the latest run; mixing versions would average different models |

`GET /api/v1/devices/{id}/forecast/accuracy/points?horizon&hours&limit` returns the drawer rows:
`target_at`, `horizon_minutes`, `predicted_pm25`, `actual_pm25`, `error`, `issued_at`,
`model_version`, `evaluated_at` — newest target first.

Rules for the UI:

- Never present `100 − MAPE` or any single "accuracy percentage". PM near zero makes a relative error
  meaningless; the two indicators above are absolute.
- Never show `100 %` before a pair exists. With `matched = 0` show "Chưa đủ mốc đối chiếu".
- Below 20 matched pairs the panel still shows the computed values but labels them
  "Đang thu thêm dữ liệu".
- The historical forecast line on the chart uses a fixed 5-minute horizon: at each target `t` it
  shows the run issued around `t − 5 min`. A run published after its target is never drawn as a
  historical forecast.

---

# 14. Forecast path (V6.3)

A Chronos run produces 360 values on the 5-second grid, not three points. Storing them as rows
would be 6.2 M rows/day/device; they are stored as one array per run instead.

## 14.1 Storage

```sql
CREATE TABLE forecast_paths (
    run_id       UUID PRIMARY KEY REFERENCES forecast_runs (id) ON DELETE CASCADE,
    anchor_at    TIMESTAMPTZ NOT NULL,
    anchor_pm25  REAL NOT NULL,
    step_seconds INTEGER NOT NULL DEFAULT 5,
    values       REAL[] NOT NULL,     -- 360 values, +5 s .. +30 min
    q10          REAL[],
    q90          REAL[],
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

| Column | Notes |
| :--- | :--- |
| `anchor_at`, `anchor_pm25` | The measured sample the curve starts from. Not part of `values`; the chart connects the last measurement to `values[0]` itself |
| `step_seconds` | 5. Target `k` (1-based) is `anchor_at + k × step_seconds`. No timestamps are stored per value |
| `values` | `REAL[360]`. The median path |
| `q10`, `q90` | Optional 10/90 band. `null` when the model did not produce one |

Retention: 48 hours by default (`AI_PATH_RETENTION_HOURS`). The retention job deletes a path only
after its evaluation points (5/15/30 min) are no longer `PENDING`; a pending point keeps its run.
`forecast_points` keeps the three scored milestones and is retained longer (14 days).

## 14.2 Upload

`POST /internal/v1/ai/forecast-runs` gains one optional object:

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "model_version": "chronos-2-lora-d67f566",
  "input_cutoff": "2026-11-18T12:00:00Z",
  "issued_at": "2026-11-18T12:00:01Z",
  "points": [ "...three milestones, unchanged..." ],
  "path": {
    "anchor_at": "2026-11-18T12:00:00Z",
    "anchor_pm25": 28.4,
    "step_seconds": 5,
    "values": [28.5, 28.7, "...360 values..."],
    "q10": [27.1, "...360 values..."],
    "q90": [29.6, "...360 values..."]
  }
}
```

Rules:

- `path.values` must have exactly `1800 / step_seconds` entries (360 at the default). A wrong length
  is a `400`, not a silently truncated curve.
- `anchor_at` must equal `input_cutoff` when both are present; the anchor is the last input sample.
- Duplicate uploads (same device + model + `input_cutoff`) return the stored run and do **not** emit
  a second SSE `forecast` event, so a retried upload cannot make the chart jump backwards.
- A run without `path` is still valid (the existing three-point behaviour); the UI falls back to the
  three points.

## 14.3 Read

`GET /api/v1/devices/{id}/forecast` and the `forecast` SSE event return the same envelope with an
optional `path`:

```json
{
  "run": { "id": "…", "model_version": "…", "issued_at": "…", "input_cutoff": "…" },
  "points": [ "...three milestones..." ],
  "path": { "anchor_at": "…", "anchor_pm25": 28.4, "step_seconds": 5, "values": [ "...360..." ] }
}
```

`GET /api/v1/devices/{id}/forecast/path?run_id=…` returns one stored curve (latest run when the
parameter is omitted). `404` when the run exists but its curve has been pruned.

## 14.4 Worker context cache

Reloading 60 minutes of history on every packet is 720 rows per 5 s, per device, for no benefit.
The worker keeps an in-memory cache and only asks for what it has not seen:

| Endpoint | Returns |
| :--- | :--- |
| `GET /internal/v1/ai/devices/{id}/samples?after_id=…&limit=500` | Telemetry rows with `id > after_id`, oldest first; plus `next_after_id` |
| `GET /internal/v1/ai/devices/{id}/actuator-reports?after_id=…&limit=200` | Actuator reports with `id > after_id`, oldest first |

Rules:

- On start, or after a gap the cache cannot cover, the worker loads the full 60-minute window once
  through `/recent`; after that it polls `after_id`.
- `after_id` is the row id, not a timestamp: two rows can share a millisecond.
- A cache miss (older than the context window, or server restart with a lower id) triggers a full
  reload, never a partial guess.
- Inference stays one task per device; while one runs, only the newest pending request is kept.
  The number of dropped requests is reported in the heartbeat capabilities.
- Telemetry carries no fan level: the node reports it on a separate topic. The cache joins the
  actuator stream as-of the **receive** clock, so a sample is described only by fan reports the
  backend already had when the sample arrived. A sample older than the first report has no known
  fan state (`NaN`), never a guessed one.

## 14.5 Context readiness

The context is a 720-step grid ending at the newest usable sample. Two independent rules decide
whether a forecast may be issued:

| Rule | Threshold | Why |
| :--- | :--- | :--- |
| Coverage | ≥ 80 % of the 720 steps carry a usable PM sample | A mostly-empty window is not a context |
| Longest hole | ≤ 60 steps (5 minutes) | Coverage alone hides an outage: 120 missing steps is still 83 %, and a curve fitted across a ten-minute hole is a guess wearing a confidence band |

A sample snaps to its nearest grid point, keeping the newest per point. A device reporting every
5.02 s drifts by 0.02 s per packet but stays within half a step of a grid point for hundreds of
packets, so a healthy stream stays near 100 % coverage. Requiring a sample within half a step *in
one direction* was the earlier bug and dropped about half of such a stream.

Missing steps stay `NaN`; they are never interpolated.

## 14.6 Latency and honesty

- The target is sensor → chart ≤ 5 s and warm inference P95 ≤ 3 s. Measured on this machine:
  P95 **0.017 s** for 720 → 360, so the remaining budget belongs to the network and the upload.
- A path is drawn **only** at the target times it was issued for. A run whose `anchor_at` is older
  than the one on screen must not move the chart backwards.
- If the worker cannot keep up, the chart keeps the previous curve and the UI says so. No value is
  ever synthesised to fill the gap.
