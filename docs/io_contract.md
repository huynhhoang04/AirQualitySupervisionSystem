# AQSS V3 — I/O Contract

> **Single source of truth for the data contracts between the layers.**
> **Version 3.0** — backend rewritten to the minimum that the system actually needs.

## What changed from 2.1

| Removed | Why |
| :--- | :--- |
| JWT / users / RBAC / device API keys | A device identifies itself with `device_id`; the dashboard is open |
| STOMP `/ws` topics | The dashboard polls two REST endpoints instead |
| AQI / NowCast, alerts, `control_decisions`, `space_thresholds` cache | Derived display metrics and audit tables nobody read |
| Monthly partitions, materialized views | A few rows per minute does not need either |
| `space_devices`, `actuators` tables | A device carries `space_code` and its own fan state |

The **firmware contract did not change**: a node still publishes telemetry / status / actuator state
and still receives the same commands.

---

## 1. MQTT

Broker: any MQTT v3.1.1 broker reachable over the internet (HiveMQ Cloud, EMQX Cloud, mosquitto).
TLS brokers use port 8883; the firmware ships the ISRG Root X1 CA and enables it with `MQTT_USE_TLS=1`.

| Topic | Direction | QoS | Retain | Payload |
| :--- | :--- | :---: | :---: | :--- |
| `aqss/devices/{id}/telemetry` | device → server | 1 | no | §2, every 5 s (`set_interval`) |
| `aqss/devices/{id}/actuator/state` | device → server | 1 | no | §3, on change + every 5 s while ON |
| `aqss/devices/{id}/status` | device → server | 1 | **yes** | §4, `ONLINE` on connect, `OFFLINE` via LWT |
| `aqss/devices/{id}/command` | server → device | 1 | no | §5 |

## 2. Telemetry payload

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "space_id": "SP_201",
  "timestamp": 1724217600,
  "sensors": { "pm25": 35.4, "co2": 620.0, "temperature": 29.5, "humidity": 68.2 },
  "system":  { "wifi_rssi": -65, "uptime_sec": 3600, "free_heap": 245760, "min_free_heap": 185000 }
}
```

| Field | Unit | Notes |
| :--- | :---: | :--- |
| `device_id` | — | ≤ 32 chars. **Auto-registered** on the first message — no provisioning step |
| `space_id` | — | Optional; links the device to a space (`spaces.code`) |
| `timestamp` | s (epoch) | NTP; the server falls back to its own clock if `< 1.7e9` |
| `sensors.pm25` | µg/m³ | GP2Y1023 `(W − 1400) / 14`, 0–500 |
| `sensors.co2` | ppm | SCD40, 400–5000 (alert only, never actuates) |
| `sensors.temperature` / `humidity` | °C / % | SCD40 co-read |
| `system.*` | — | Diagnostics only |

## 3. Actuator state payload

```json
{ "device_id": "AQSS_ESP32S3_01", "timestamp": 1724217600, "state": "ON",
  "level": 2, "duty_pct": 60, "mode": "AUTO", "filter_runtime_h": 123.4 }
```

## 4. Status + LWT

```json
{ "device_id": "AQSS_ESP32S3_01", "status": "ONLINE", "timestamp": 1724217600 }
```

The firmware registers the will on `aqss/devices/{id}/status` (retain, QoS 1, `{"status":"OFFLINE"}`), so
a dead node shows up as OFFLINE without a watchdog job here.

## 5. Commands (server → device)

```json
{ "command": "set_actuator", "params": { "state": "ON", "level": 2 } }
```

| Command | Params | Effect |
| :--- | :--- | :--- |
| `set_actuator` | `state: ON\|OFF`, `level: 0–3` | PWM duty 0 / 35 / 60 / 90 % |
| `display_warning` | `message`, `severity: INFO\|WARNING\|CRITICAL` | Text band on the OLED (30 min TTL) |
| `set_space` | `space_id` | Persisted to NVS |
| `set_interval` | `interval_ms` | Telemetry period (1000–60000) |
| `recalibrate_dust` | `baseline_w0` (opt.) | GP2Y1023 baseline; defaults to the current pulse |
| `force_ntp_sync` | — | Immediate NTP resync |
| `reboot` | — | `ESP.restart()` |

The firmware null-checks `command` before comparing, so a malformed payload cannot crash the node.

---

## 6. Database (Flyway `V1__init.sql`)

| Table | Columns | Notes |
| :--- | :--- | :--- |
| `spaces` | `code` PK, `name`, `volume_m3`, `purpose`, `region`, `ventilation_estimate`, `threshold_pm25`, `threshold_source` (`manual`/`ai`), `updated_at` | Threshold lives **on the space** |
| `devices` | `device_id` PK, `name`, `space_code` FK, `fan_state`, `fan_level`, `fan_mode`, `on_since`, `last_command_at`, `telemetry_interval_ms`, `dust_baseline_us`, `last_seen_at`, `created_at` | Created on first telemetry |
| `telemetry` | `id`, `device_id` FK, `recorded_at`, `pm25`, `co2`, `temperature`, `humidity`, `wifi_rssi` | Index `(device_id, recorded_at DESC)` |
| `predictions` | `id`, `device_id` FK, `predicted_at`, `model_version`, `points` (JSON text) | 96 quantile points from Layer 3 |
| `audit_log` | `id`, `occurred_at`, `actor` (`USER`/`BACKEND`/`DEVICE`/`AI`), `action`, `device_id`, `detail` (JSON text) | One log for every action |

## 7. REST API

JSON is **snake_case** everywhere (platform-wide Jackson setting). No authentication.

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `GET` | `/api/v1/devices` | All devices with live fan state |
| `GET` | `/api/v1/devices/{id}` | One device |
| `PUT` | `/api/v1/devices/{id}` | `{name, space_code, telemetry_interval_ms, dust_baseline_us}` → also pushed to the node |
| `DELETE` | `/api/v1/devices/{id}` | Forget the device |
| `POST` | `/api/v1/devices/{id}/command` | Any command from §5 |
| `GET` | `/api/v1/devices/{id}/latest` | Newest reading (map card) |
| `GET` | `/api/v1/devices/{id}/telemetry?from&to` | Chart data, default last 24 h |
| `GET` | `/api/v1/devices/{id}/forecast` | Stored 96-point forecast (`[]` until one exists) |
| `POST` | `/api/v1/devices/{id}/forecast` | Resample 24 h → Layer 3 → store → return |
| `GET` | `/api/v1/spaces` | Spaces + thresholds |
| `GET` | `/api/v1/spaces/{code}` | One space |
| `PUT` | `/api/v1/spaces/{code}` | Upsert `{name, volume_m3, purpose, region, ventilation_estimate, threshold_pm25}` |
| `DELETE` | `/api/v1/spaces/{code}` | Delete |
| `POST` | `/api/v1/spaces/{code}/threshold` | Ask ML#1 for a threshold (no-op while `AI_ENABLED=false`) |
| `GET` | `/api/v1/audit?device_id&limit` | Audit trail, newest first |
| `GET` | `/actuator/health` | Liveness |

## 8. Layer 3 API (backend is the caller)

| Method | Endpoint | Request | Response |
| :--- | :--- | :--- | :--- |
| POST | `/api/v1/threshold` | `{space_id, volume_m3, purpose, region, ventilation_estimate, occupancy_typical}` | `{space_id, base_threshold_pm25, uncertainty_lower, uncertainty_upper, model_version}` |
| POST | `/api/v1/dosage` | `{space_id, volume_m3, purpose, current_pm25, base_threshold, outdoor_pm25_estimate, filter_cadr}` | `{space_id, filter_level, run_duration_minutes, model_version}` |
| POST | `/api/v1/forecast` | `{space_id, series:[{timestamp, pm25}] × ≤96}` | `{space_id, status, model_version, forecast_sequence:[{target_time, pm25_q10, pm25_q50, pm25_q90}]}` |

`filter_level` maps to PWM duty 0/35/60/90 %. Every call is skipped when `AI_ENABLED=false`, and a
failure never blocks the control loop (the rule-based fallbacks are the defaults in `ControlLoop`).

## 9. Sensor acceptance window (firmware and backend enforce the same table)

| Sensor | Unit | Valid | Reject |
| :--- | :---: | :--- | :--- |
| PM2.5 | µg/m³ | 0.0 – 500.0 | outside |
| CO2 | ppm | 400 – 5000 | outside |
| Temperature | °C | −10 – 60 | outside |
| Humidity | % | 0 – 100 | outside |
| RSSI | dBm | −100 – 0 | — |

## 10. Control loop (every `CONTROL_CYCLE_MS`, default 60 s)

```
for each device with fan_mode = AUTO and a space_code:
    pm25 = latest reading
    fan off?  pm25 >= threshold                          -> ACTIVATE (level from ML#2, else 2)
    fan on?   pm25 <  0.7 × threshold  and  min-ON ≥ 5m   -> DEACTIVATE
```

Every activation/deactivation is written to `audit_log` with `actor = BACKEND`.
