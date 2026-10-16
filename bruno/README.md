# Bruno — AQSS API

Bruno workspace for testing the **AQSS V2** backend (Layer 2, "the brain"). REST only —
the project exposes no gRPC service, so there is a single format in use (OpenCollection YAML).

- Backend: `backend/` (Spring Boot 3.3, Java 21, port 8080)
- Contract of record: `docs/io_contract.md`
- Local infrastructure: `backend/docker-compose.yml` (PostgreSQL 16 + Mosquitto)

## Import and pick an environment

1. In Bruno choose **Open Workspace** and open this folder (`bruno/`).
2. Open the environment menu (top right) and pick **local** (workspace-level, in
   `environments/`). If it does not appear, **Reload Workspace** first.
3. Fill the plain variables on the **Variables** tab and press **Save**.
4. Variables marked `secret: true` (`adminPassword`, `registerPassword`) go to the **Secret
   Variables** tab — Bruno stores them encrypted on this machine and does not write them back
   into the file. Nothing else needs to be configured.

### Local defaults

| Variable | Value | Where it comes from |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8080` | the Spring Boot app on this machine |
| `adminEmail` | `admin@aqss.local` | seeded by `AdminSeeder` on an empty database |
| `adminPassword` | **secret** — `admin12345` for local dev | same seeder; change before any real deployment |
| `spaceCode` | `SP_201` | seeded by Flyway `V4__spaces_and_actuation.sql` |
| `deviceId` | `AQSS_ESP32S3_01` | seeded by the same migration |

## Auth flow (run in this order)

1. **`01 - Auth` / `02 - Auth - Token`** — logs in and captures `access_token` /
   `refresh_token` into Bruno **runtime** variables via `bru.setVar()`. Tokens are never
   written to an environment file.
2. Every protected request sends `Authorization: Bearer {{accessToken}}`.
3. **`01 - Auth` / `03 - Auth - Refresh`** exchanges the captured refresh token for a new
   access token (access lives 30 min, refresh 7 days).
4. A `before-request` script on each protected and public request fails loudly when its
   required variable is missing, instead of sending an empty credential.

Role-based access: `POST/PUT/DELETE /api/v1/spaces/**`, `POST /api/v1/devices` and
`POST /api/v1/forecast/run` are **ADMIN only**. Sending them with a `ROLE_USER` token returns
403 — register a normal user with `01 - Auth` / `01 - Auth - Register` to verify that.

## Getting a request to return data

Telemetry reads return **404** until the node has reported at least once. Telemetry arrives
over MQTT, so either:

- start the stack with a broker — `cd backend && docker compose up -d mosquitto postgres` and
  the app without `MQTT_ENABLED=false`; or
- run the backend with `MQTT_ENABLED=false` and seed a row directly:

```bash
psql -h localhost -U aqss_user -d aqss_db -c \
  "INSERT INTO telemetry_data (recorded_at, device_id, space_id, pm25, co2, temperature, humidity) \
   VALUES (NOW(), 'AQSS_ESP32S3_01', 'SP_201', 40, 600, 29, 60);"
```

With the default 60 s control cycle that reading is above the seeded `SP_201` threshold, so
`02 - Spaces` / `08 - Spaces - Decisions` will show an `ACTIVATE / threshold_breach` row and
`04 - Devices and Actuators` / `01 - Actuators - List` will show the fan moved. Set
`AQSS_CONTROL_CYCLE_MS=5000` to make the loop run every 5 s while testing.

## Reaching other targets

`{{baseUrl}}` is a loopback URL. For a remote deploy, add a gitignored
`environments/<target>.local.yml` (e.g. `staging`, `vps`) with the real host and pick it in the
environment menu — never edit `local.yml` to point at a shared environment. If the backend is
bound to loopback on a remote host, tunnel instead of opening the port:

```bash
ssh -N -L 8080:127.0.0.1:<remote-port> <user>@<host>
```

Never open a backend port to the internet just to make testing easier.

## Safety notes

- **Read-only and always safe to run:** `04 - Devices and Actuators` / `04 - Health - Liveness`,
  all `GET` requests, `01 - Auth` / `03 - Auth - Refresh`.
- **Side effects — send one at a time from the app, never via Collection Runner:**
  `02 - Spaces` / `02 - Spaces - Create` (writes a space + threshold),
  `04 - Spaces - Update` (recomputes the threshold),
  `05 - Spaces - Assign device` (writes the mapping **and publishes MQTT `set_space`**),
  `06 - Spaces - Recompute threshold`,
  `07 - Spaces - Actuator manual` (**drives the physical fan and writes a MANUAL decision**),
  `04 - Devices and Actuators` / `03 - Devices - Create` (returns a secret API key once),
  `05 - Forecast` / `02 - Forecast - Run`,
  `06 - Alerts` / `02 - Alerts - Resolve`.
- **Destructive:** `02 - Spaces` / `10 - Spaces - Delete (destructive)` deletes
  `{{newSpaceCode}}` (`SP_902`) and cascades its mapping and threshold rows. It deliberately
  does **not** target `{{spaceCode}}` (`SP_201`), so the demo space survives a misclick.
- This API has **no idempotency-key mechanism** — every write is fire-once. Re-running a create
  returns `409` (unique constraint) rather than replaying; that is the replay guard.
- Secrets live only in the Secret Variables tab or a gitignored `*.local.yml`. If a value was
  ever committed, rotate it — deleting it from a later commit is not enough.
- No script logs a token, a password or a full response body.

## Inventory

| Collection | Requests | Notes |
| --- | ---: | --- |
| `01 - Auth` | 3 | register (once), token (captures JWT), refresh |
| `02 - Spaces` | 9 | list, create, get by code, update, assign device, recompute threshold, actuator manual, decisions, delete |
| `03 - Telemetry` | 3 | latest (+ derived AQI/`co2_alert`), history, aggregate |
| `04 - Devices and Actuators` | 4 | actuators, devices, create device, health (public) |
| `05 - Forecast` | 2 | latest (empty until Phase 8), run |
| `06 - Alerts` | 2 | list (captures `alertId`), resolve |

Total: **23 requests**. Request files are `NN-<entity>---<action>.yml`, and `info.name` /
`info.seq` always agree with the filename number. Numbers are stable — insert new requests at
the next free number rather than renumbering (10 is reserved for the destructive delete).

## Not covered here

- **MQTT topics** (`telemetry`, `status`, `actuator/state`, `command`) and the **STOMP**
  channels (`/topic/spaces/{id}/decision`, `/topic/actuators/{id}`, …) are push channels, not
  REST. Exercising them needs an MQTT client and a STOMP client, not Bruno. See
  `docs/io_contract.md` §7–§8.
- **Layer 3 (AI)** endpoints `/api/v1/threshold`, `/api/v1/dosage`, `/api/v1/forecast` are
  called server-to-server by the backend, not by the dashboard — they belong to a separate
  workspace when `layer3_dl/` is implemented (Phase 8–9).
