# AQSS V5 — I/O Contract

> **Single source of truth for the data contracts between the layers.**
> **Version 5.0** — the external AI service is gone. Three layers remain: node, backend, dashboard.

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
| `POST` | `/api/v1/devices/pair` | `{pairing_code, space_name, name?, purpose?, volume_m3?, occupancy_hours?}` → link a node, creating the room if needed |
| `POST` | `/api/v1/devices/{id}/unpair` | Drop the binding **and** tell the node to forget it |
| `POST` | `/api/v1/devices/{id}/filter` | "New filter fitted" — resets the counter on both sides |
| `POST` | `/api/v1/devices/{id}/command` | Any command from §5 |
| `GET` | `/api/v1/devices/{id}/latest` | Newest reading |
| `GET` | `/api/v1/devices/{id}/telemetry?from&to` | Chart data, default last 24 h, thinned to ~800 points |
| `GET` | `/api/v1/spaces` | Rooms + thresholds |
| `GET` | `/api/v1/spaces/{code}` | One room |
| `PUT` | `/api/v1/spaces/{code}` | Patch `{name, volume_m3, purpose, region, ventilation_estimate, threshold_pm25}` |
| `DELETE` | `/api/v1/spaces/{code}` | Delete |
| `GET` | `/api/v1/spaces/{code}/threshold/preview` | The threshold the profile implies, **without saving** |
| `POST` | `/api/v1/spaces/{code}/threshold` | Compute and store it (`threshold_source: profile`) |
| `GET` | `/api/v1/audit?device_id&limit` | Audit trail, newest first |
| `GET` | `/actuator/health` | Liveness |

### 7.1 Pairing

```json
{ "pairing_code": "X35AJK", "space_name": "Phòng ngủ", "name": "Máy phòng ngủ",
  "purpose": "bedroom", "volume_m3": 15, "occupancy_hours": 8 }
```

* The code is normalised (uppercase, non-alphanumerics stripped) and matched against
  `devices.pairing_code`.
* When `space_name` is given and no such room exists, one is created: the code is derived from the
  ASCII skeleton of the name (`Phòng ngủ tầng 2` → `PHONG_NGU_TANG_2`), and its threshold is computed
  from the profile in the same request.
* `404` — no node has connected with that code. `400` — invalid code. `409` — already paired elsewhere.

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
