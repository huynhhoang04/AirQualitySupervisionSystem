# Layer 2 — Backend (as built)

> **Status:** implemented. 23 Java files / ~1.3 kLOC. Contracts: [`io_contract.md`](./io_contract.md).
> **Design rule:** the backend does four things — collect, serve, command, call Layer 3. Anything that
> did not serve one of those was removed (see the "removed" table at the end).

---

## 1. What it does

```text
            ┌─────────────── Render / any JVM host ───────────────┐
  ESP32 ──► │ MqttLink ──► TelemetryService ──► Postgres (Neon)   │ ◄── dashboard (REST)
  (MQTT,    │    │              │                                │
   TLS)     │    │              └── ControlLoop ──► DeviceService ┼──► set_actuator / config
            │    └── DeviceService (status, actuator state)        │
            │                    Service ──► AiClient ────────────┼──► Layer 3 (FastAPI)
            └────────────────────────────────────────────────────┘
```

| Component | File | Responsibility |
| :--- | :--- | :--- |
| `MqttLink` | `mqtt/MqttLink.java` | One Paho client: subscribe `telemetry` / `status` / `actuator/state`, publish `command`. Inbound payloads become Spring events |
| `TelemetryService` | `service/TelemetryService.java` | Validate against §9, auto-register the device, store, serve history/latest, build the 96-point forecast |
| `DeviceService` | `service/DeviceService.java` | Registry, config (`set_space`/`set_interval`/`recalibrate_dust`), every command, fan state, status/LWT |
| `SpaceService` | `service/SpaceService.java` | Spaces + threshold (manual or ML#1) |
| `AiClient` | `service/AiClient.java` | The three Layer-3 calls: threshold, dosage, forecast |
| `ControlLoop` | `service/ControlLoop.java` | 60 s cycle: threshold breach → ACTIVATE, hysteresis → DEACTIVATE |
| `AuditService` | `service/AuditService.java` | One audit row for every action (user, backend, device, AI) |

Threading: Paho delivers on its own thread and publishes Spring events; the services are
`@Transactional`, so the DB work happens on the caller thread with one connection per message. At one
message per 5 s per node there is no need for a queue or a batch writer.

## 2. Data model

Five tables (`V1__init.sql`): `spaces`, `devices`, `telemetry`, `predictions`, `audit_log`.
No auth tables, no partitions, no materialized views. Details in `io_contract.md` §6.

## 3. Configuration

Everything is an environment variable with a working default (`application.yml` + `.env.example`):

| Key | Default | Purpose |
| :--- | :--- | :--- |
| `DB_URL` / `DB_USER` / `DB_PASS` | local Postgres | Neon: append `?sslmode=require` |
| `MQTT_BROKER_URL` | `tcp://localhost:1883` | `ssl://<host>:8883` for a cloud broker |
| `MQTT_USER` / `MQTT_PASS` | `aqss_device` / `aqss_password` | |
| `AI_BASE_URL` / `AI_ENABLED` | `http://localhost:8000` / `false` | Layer 3 |
| `CONTROL_CYCLE_MS` | `60000` | Control-loop period |
| `CONTROL_HYSTERESIS_RATIO` | `0.7` | Fan OFF below 70 % of the threshold |
| `CONTROL_MIN_ON_MINUTES` | `5` | Anti-flapping |
| `CORS_ORIGIN` | `http://localhost:5173` | Dashboard origin |
| `PORT` / `SERVER_PORT` | `8080` | Render injects `PORT` |

## 4. Deploy (Render + Neon + a cloud broker)

Render cannot host MQTT (it only exposes HTTP), so the broker is external — HiveMQ Cloud free tier
works and needs no server:

1. **Neon** — create a project, copy the connection string
   (`postgresql://…` → `jdbc:postgresql://…?sslmode=require`). Flyway creates the schema on first boot.
2. **Broker** — create a HiveMQ Cloud free cluster, keep the credentials, note `ssl://<host>:8883`.
3. **Render** — `New + → Blueprint`, blueprint path `backend/render.yaml`, then fill the
   `sync: false` values (Neon URL/user/pass, broker URL/user/pass, `AI_BASE_URL`, `CORS_ORIGIN`).
4. **Firmware** — `include/secrets.h`: SSID/password, `MQTT_SERVER`, `MQTT_USE_TLS 1`, broker user/pass.

Free-tier notes: Render sleeps after 15 min idle (first request takes ~40 s); Neon suspends idle
compute (the pool in `application.yml` is sized at 5 for that reason).

## 5. Removed from the V2 design (and why)

| Removed | Reason |
| :--- | :--- |
| JWT, users, RBAC, device API keys | The device authenticates by `device_id`; the dashboard is open (see `io_contract.md` §7) |
| STOMP `/ws` topics, `spring-boot-starter-websocket` | The dashboard polls REST |
| AQI / NowCast | Display-only metric; the frontend can compute it |
| `alerts`, `control_decisions`, `space_thresholds` | A single `audit_log` covers every action |
| `space_devices`, `actuators` | `devices.space_code` + fan columns on the device row |
| Monthly partitions, materialized views, batch queue | Volume is a few rows per minute |
| MapStruct, 29 DTOs, 5 mappers | Entities are the API shape; commands take a small record |
| `ForecastService`, `AdminSeeder`, `PartitionManagerService`, `AlertService`, `ThresholdService`, `DosageService` | Folded into the seven components above |

**Not removed:** the firmware contract (§1–§5) and the Layer-3 contract (§8) are byte-for-byte the same
as V2, so the ESP32 and the FastAPI service need no change beyond the broker host/TLS.
