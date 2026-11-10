# AQSS V4 — I/O Contract

> **Single source of truth for the data contracts between the layers.**
> **Version 4.0** — adds device pairing, closes the control loop, and makes every
> value on the wire mean exactly one thing.

## What changed from 3.0

| Change | Why |
| :--- | :--- |
| **Pairing code** on the status + telemetry payloads, and `POST /devices/pair` | A node must not appear on the dashboard until an operator links it to a room. The OLED shows the code; the dashboard asks for it |
| `devices.paired_at` / `devices.pairing_code`, `devices.off_since` | Pairing state, the code lookup, and the min-OFF timer |
| `set_actuator` gains `mode` (`AUTO`/`MANUAL`) | The firmware used to force AUTO on every command, so a dashboard override lasted one telemetry period |
| `sensors.*` may be `null` | A sensor that has no reading yet is *absent*, not `0.0` (which the backend rejects as out of range) |
| `system.pm25_saturated` / `system.dust_fault` | A GP2Y1023 at its rail reports ~393 µg/m³ as if it were real; the flag travels with the reading |
| `telemetry.pm25_saturated` column | The dashboard can say "saturated", not "393 µg/m³" |
| Forecast pre-emption + min-OFF/max-ON in the control loop | The project's headline claim: actuate *before* the breach |
| `GET /audit` query names (`device_id`) | `@RequestParam` binds the Java parameter name; the UI was sending `device_id` and the filter silently did nothing |
| `GET /devices` returns only paired devices | Unpaired telemetry is stored but hidden |

Everything else from 3.0 stands.

---

## 1. MQTT

Broker: any MQTT v3.1.1 broker reachable over the internet (EMQX Cloud, HiveMQ, mosquitto).
TLS brokers use port 8883; the firmware ships the DigiCert Global Root G2 CA and enables TLS with
`MQTT_USE_TLS=1`.

| Topic | Direction | QoS | Retain | Payload |
| :--- | :--- | :---: | :---: | :--- |
| `aqss/devices/{id}/telemetry` | device → server | 1 | no | §2, every 5 s (`set_interval`) |
| `aqss/devices/{id}/actuator/state` | device → server | 1 | no | §3, on any change + every 5 s while ON |
| `aqss/devices/{id}/status` | device → server | 1 | **yes** | §4, `ONLINE` on connect, `OFFLINE` via LWT |
| `aqss/devices/{id}/command` | server → device | 1 | no | §5 |

The broker may replay the retained `status` message on every backend connect. That is expected and
idempotent: `status` is only used to learn the device exists, its pairing code, and liveness.

## 2. Telemetry payload

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "space_id": "SP_201",
  "pairing_code": "7K2M9X",
  "timestamp": 1724217600,
  "sensors": { "pm25": 35.4, "co2": 620.0, "temperature": 29.5, "humidity": 68.2 },
  "system":  { "wifi_rssi": -65, "uptime_sec": 3600, "free_heap": 245760,
               "min_free_heap": 185000, "pm25_saturated": false, "dust_fault": false }
}
```

| Field | Unit | Notes |
| :--- | :---: | :--- |
| `device_id` | — | ≤ 32 chars. The node's identity; a row is created on first sight |
| `space_id` | — | Optional. **Advisory only** — the room binding is set by pairing (§7), never by the node |
| `pairing_code` | — | 6 chars from `A–Z2–9` (no `I/O/0/1`). Derived from the chip MAC, stable across reboots. Shown on the OLED |
| `timestamp` | s (epoch) | NTP; the server falls back to its own clock if `< 1.7e9` |
| `sensors.pm25` | µg/m³ | GP2Y1023 `(W − W₀) / 14`; `null` until the first full 1 s sample window |
| `sensors.co2` / `temperature` / `humidity` | ppm / °C / % | SCD40; `null` until its first periodic reading |
| `system.pm25_saturated` | — | The pulse width reached `DUST_SATURATION_US`; the value is a floor, not a measurement |
| `system.dust_fault` | — | No valid pulse for 3 s — the sensor is unplugged or its LED is dead |

**Null is not zero.** A field that is `null` means "no reading yet"; it is stored as SQL `NULL` and
is not a validation failure. `0.0` in `co2` would be out of range and is rejected (§9).

## 3. Actuator state payload

```json
{ "device_id": "AQSS_ESP32S3_01", "timestamp": 1724217600, "state": "ON",
  "level": 2, "duty_pct": 60, "mode": "AUTO", "filter_runtime_h": 123.4 }
```

Published when `state`, `level` or `mode` changes, and every 5 s while the fan is ON.

## 4. Status + LWT

```json
{ "device_id": "AQSS_ESP32S3_01", "pairing_code": "7K2M9X",
  "status": "ONLINE", "timestamp": 1724217600 }
```

The firmware registers the will on `aqss/devices/{id}/status` (retain, QoS 1, `{"status":"OFFLINE"}`),
so a dead node shows up as OFFLINE without a watchdog job here. `OFFLINE` never advances
`last_seen_at` — only `ONLINE` does.

## 5. Commands (server → device)

```json
{ "command": "set_actuator", "params": { "state": "ON", "level": 2, "mode": "AUTO" } }
```

| Command | Params | Effect |
| :--- | :--- | :--- |
| `set_actuator` | `state: ON\|OFF`, `level: 0–3`, `mode: AUTO\|MANUAL` (default `AUTO`) | Duty 0 / 35 / 60 / 90 %. `mode` is applied to the node; it also renews the 10 min fail-safe lease |
| `display_warning` | `message`, `severity: INFO\|WARNING\|CRITICAL` | Text band on the OLED (30 min TTL) |
| `set_space` | `space_id` | Persisted to NVS (advisory display only; the binding lives in the backend) |
| `set_interval` | `interval_ms` | Telemetry period (1000–60000) |
| `recalibrate_dust` | `baseline_w0` (opt.) | GP2Y1023 baseline; defaults to the current pulse |
| `force_ntp_sync` | — | Immediate NTP resync |
| `reboot` | — | `ESP.restart()` |

The firmware null-checks `command` before comparing, so a malformed payload cannot crash the node.
A `set_actuator` in `AUTO` mode is a **lease renewal**: the backend republishes it at least every
5 minutes while a fan is running so the node's fail-safe does not cut a healthy fan.

---

## 6. Database (Flyway `V1__init.sql` + `V2__pairing.sql`)

| Table | Columns | Notes |
| :--- | :--- | :--- |
| `spaces` | `code` PK, `name`, `volume_m3`, `purpose`, `region`, `ventilation_estimate`, `threshold_pm25`, `threshold_source` (`manual`/`ai`), `updated_at` | Threshold lives **on the space** |
| `devices` | `device_id` PK, `name`, `space_code` FK, `pairing_code`, `paired_at`, `fan_state`, `fan_level`, `fan_mode`, `on_since`, `off_since`, `last_command_at`, `telemetry_interval_ms`, `dust_baseline_us`, `last_seen_at`, `created_at` | Created on first message, **unpaired**; `paired_at` is set by §7 |
| `telemetry` | `id`, `device_id` FK, `recorded_at`, `pm25`, `co2`, `temperature`, `humidity`, `wifi_rssi`, `pm25_saturated` | Index `(device_id, recorded_at DESC)` |
| `predictions` | `id`, `device_id` FK, `predicted_at`, `model_version`, `points` (JSON text) | 96 quantile points from Layer 3 |
| `audit_log` | `id`, `occurred_at`, `actor` (`USER`/`BACKEND`/`DEVICE`/`AI`), `action`, `device_id`, `detail` (JSON text) | One log for every action |

`V2__pairing.sql` adds `devices.pairing_code`, `devices.paired_at`, `devices.off_since`,
`telemetry.pm25_saturated`, an index on `devices.pairing_code`, and back-fills `paired_at` for rows
that already have a `space_code` (the seeded demo device).

## 7. REST API

JSON is **snake_case** everywhere (platform-wide Jackson setting). No authentication.

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `GET` | `/api/v1/devices` | **Paired** devices with live fan state (`?include_unpaired=true` for all) |
| `GET` | `/api/v1/devices/{id}` | One device |
| `PUT` | `/api/v1/devices/{id}` | Patch `{name, space_code, telemetry_interval_ms, dust_baseline_us}`; `null` fields are left untouched |
| `DELETE` | `/api/v1/devices/{id}` | Forget the device (the node re-registers unpaired on its next message) |
| `POST` | `/api/v1/devices/pair` | `{pairing_code, space_code, name?}` → link an unpaired node to a room |
| `POST` | `/api/v1/devices/{id}/unpair` | Drop the room binding; telemetry keeps arriving but is hidden |
| `POST` | `/api/v1/devices/{id}/command` | Any command from §5 |
| `GET` | `/api/v1/devices/{id}/latest` | Newest reading (map card) |
| `GET` | `/api/v1/devices/{id}/telemetry?from&to` | Chart data, default last 24 h |
| `GET` | `/api/v1/devices/{id}/forecast` | Stored 96-point forecast (`[]` until one exists) |
| `POST` | `/api/v1/devices/{id}/forecast` | Resample 24 h → Layer 3 → store → return |
| `GET` | `/api/v1/spaces` | Spaces + thresholds |
| `GET` | `/api/v1/spaces/{code}` | One space |
| `PUT` | `/api/v1/spaces/{code}` | Patch `{name, volume_m3, purpose, region, ventilation_estimate, threshold_pm25}`; `null` fields are left untouched |
| `DELETE` | `/api/v1/spaces/{code}` | Delete |
| `POST` | `/api/v1/spaces/{code}/threshold` | Ask ML#1 for a threshold (no-op while `AI_ENABLED=false`) |
| `GET` | `/api/v1/audit?device_id&limit` | Audit trail, newest first |
| `GET` | `/actuator/health` | Liveness |

### 7.1 Pairing

`POST /api/v1/devices/pair`

```json
{ "pairing_code": "7K2M9X", "space_code": "SP_201", "name": "Node phòng 201" }
```

* The code is normalised (uppercase, non-alphanumerics stripped) and matched against
  `devices.pairing_code`.
* `404` — no node has ever connected with that code. The node must have published `status` or
  telemetry at least once.
* `400` — unknown `space_code`.
* `409` — the device is already paired to a different space (call `unpair` first).
* Success sets `paired_at`, `space_code`, `name` (when provided), publishes `set_space`, writes
  `audit_log(USER, PAIR)` and returns the device.

### 7.2 Device JSON

```json
{ "device_id": "AQSS_ESP32S3_01", "name": "Node phòng 201", "space_code": "SP_201",
  "pairing_code": "7K2M9X", "paired_at": "2026-11-10T03:00:00Z",
  "fan_state": "OFF", "fan_level": 0, "fan_mode": "AUTO",
  "on_since": null, "off_since": null, "last_command_at": null,
  "telemetry_interval_ms": 5000, "dust_baseline_us": 1400.0,
  "last_seen_at": "2026-11-10T03:00:05Z", "created_at": "2026-11-10T02:00:00Z" }
```

## 8. Layer 3 API (backend is the caller)

| Method | Endpoint | Request | Response |
| :--- | :--- | :--- | :--- |
| POST | `/api/v1/threshold` | `{space_id, volume_m3, purpose, region, ventilation_estimate, occupancy_typical}` | `{space_id, base_threshold_pm25, uncertainty_lower, uncertainty_upper, model_version}` |
| POST | `/api/v1/dosage` | `{space_id, volume_m3, purpose, current_pm25, base_threshold, outdoor_pm25_estimate, filter_cadr}` | `{space_id, filter_level, run_duration_minutes, model_version}` |
| POST | `/api/v1/forecast` | `{space_id, series:[{timestamp, pm25}] × ≤96}` | `{space_id, status, model_version, forecast_sequence:[{target_time, pm25_q10, pm25_q50, pm25_q90}]}` |

Payloads are **snake_case in both directions**; `AiClient` must use the application's configured
`RestClient.Builder` so the Jackson naming strategy applies. `filter_level` maps to PWM duty
0/35/60/90 %. Every call is skipped when `AI_ENABLED=false`, and a failure never blocks the control
loop (the rule-based fallbacks are the defaults in `ControlLoop`).

## 9. Sensor acceptance window (firmware and backend enforce the same table)

| Sensor | Unit | Valid | Reject |
| :--- | :---: | :--- | :--- |
| PM2.5 | µg/m³ | 0.0 – 500.0 | outside |
| CO2 | ppm | 400 – 5000 | outside |
| Temperature | °C | −10 – 60 | outside |
| Humidity | % | 0 – 100 | outside |
| RSSI | dBm | −100 – 0 | — |

A `null` sensor field is accepted and stored as `NULL`. The firmware drops an out-of-range sample
before it reaches the payload; the backend is the second gate.

## 10. Control loop (every `CONTROL_CYCLE_MS`, default 60 s)

Only devices with `paired_at IS NOT NULL`, `fan_mode = AUTO` and a `space_code` participate.

```
pm25  = newest reading for the device (ignored when older than 5 min)
thr   = space.threshold_pm25
breach = newest forecast has a point within 2 h with pm25_q90 >= thr
         (forecast ignored when older than 3 h)

if fan is OFF and min OFF (3 min) has elapsed:
    breach            -> ACTIVATE, level from ML#2 (reason forecast_breach)
    pm25 >= thr       -> ACTIVATE, level from ML#2 (reason threshold_breach)

if fan is ON:
    on for >= 120 min -> DEACTIVATE (reason max_on)
    pm25 < 0.7 * thr and on for >= 5 min -> DEACTIVATE (reason hysteresis_off)
    otherwise         -> republish set_actuator (lease renewal; no audit row)
```

Every activation/deactivation is written to `audit_log` with `actor = BACKEND`. A forecast breach
also pushes `display_warning` to the OLED at most once per 30 min and writes `audit_log(BACKEND,
ALERT)`. CO2 never actuates.

### 10.1 Manual override

`set_actuator` with `mode = MANUAL` (the dashboard's manual control) sets the node to MANUAL; the
backend stores `fan_mode = MANUAL` and the control loop skips the device until the operator picks
AUTO again. The physical button cycles OFF → L1 → L2 → L3 → OFF in MANUAL; holding it 3 s returns
the node to AUTO and renews the lease.
