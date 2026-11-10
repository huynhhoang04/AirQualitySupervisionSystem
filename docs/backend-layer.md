# Layer 2 — Backend (as built)

> **Status:** implemented. 25 Java files. Contracts: [`io_contract.md`](./io_contract.md).
> **Design rule:** the backend does three things — collect, decide, serve. Anything that did not
> serve one of those was removed.

---

## 1. What it does

```text
                    ┌──────────── Render / any JVM host ─────────────┐
   ESP32 ──MQTT──►  │  MqttLink ──► TelemetryService ──► Postgres    │  ◄── dashboard (REST)
   (TLS, 8883)      │      │              │                        │
                    │      │              └──► ControlLoop ────────┼──► set_actuator
                    │      └──► DeviceService (pairing, state)     │
                    └───────────────────────────────────────────────┘
```

| Component | File | Responsibility |
| :--- | :--- | :--- |
| `MqttLink` | `mqtt/MqttLink.java` | One Paho client: subscribe `telemetry` / `status` / `actuator/state`, publish `command`. Inbound payloads become Spring events |
| `TelemetryService` | `service/TelemetryService.java` | Validate against §8, store, advance liveness, evaluate the loop for that reading, serve history/latest |
| `DeviceService` | `service/DeviceService.java` | Registry, pairing/unpairing, config, every command, fan state, filter reset |
| `SpaceService` | `service/SpaceService.java` | Rooms, threshold preview and apply |
| `ThresholdService` | `service/ThresholdService.java` | **The threshold formula**: room profile + live CO₂ → µg/m³, with every factor reported |
| `SpaceCodes` | `service/SpaceCodes.java` | Vietnamese room name → ASCII code (`Phòng ngủ tầng 2` → `PHONG_NGU_TANG_2`) |
| `ControlLoop` | `service/ControlLoop.java` | Immediate evaluation on telemetry, plus a 60 s safety-net tick |
| `AuditService` | `service/AuditService.java` | One audit row per action |

Threading: Paho delivers on its own thread and publishes a Spring event synchronously; the services
are `@Transactional`, so DB work happens on that caller thread. At one message per 5 s per node there
is no need for a queue. `MqttLink` publishes best-effort — a broker outage must not fail REST requests
or the control loop; only an explicit user command reports the failure.

## 2. Data model

Four tables after `V4__drop_predictions.sql`: `spaces`, `devices`, `telemetry`, `audit_log`.
No auth tables, no partitions, no materialized views. Details in `io_contract.md` §6.

Migrations:

| Version | What |
| :--- | :--- |
| `V1__init.sql` | schema + a demo room and device |
| `V2__pairing.sql` | `pairing_code`, `paired_at`, `off_since`, `pm25_saturated` |
| `V3__filter_runtime.sql` | `filter_runtime_s`, `filter_replaced_at`, `last_reason`, `last_decision_at` |
| `V4__drop_predictions.sql` | removes the forecast cache table |

## 3. The threshold

`ThresholdService` is the only place a threshold is decided. It is a dose argument over the room's
mass balance:

```
threshold = 15 × √(24 / occupancy_hours) × ∛(V / 20) × f(ventilation) × f(CO₂)   clamped [10, 60]
```

| Factor | Range | Source |
| :--- | :--- | :--- |
| `15` | — | WHO 2021 24 h PM2.5 guideline |
| `√(24/h)` | 1.0 – 3.5 | purpose (bedroom 8 h, classroom 6 h, lobby 2 h) |
| `∛(V/20)` | 0.7 – 1.6 | `spaces.volume_m3` |
| `f(ventilation)` | 0.75 – 1.35 | `spaces.ventilation_estimate` |
| `f(CO₂)` | 0.75 – 1.0 | newest reading, `(ppm − 420) / 800` capped |

The ceiling of 60 is a health rule, not a formula output. `Result` carries every factor so
`GET /spaces/{code}/threshold/preview` can explain the number, and the dashboard renders each one.

No model is involved. An earlier version called an external service for this; the service was
removed once the formula made it redundant.

## 4. The control loop

Two entry points into the same evaluator:

1. **`ControlLoop.onReading(device)`** — called by `TelemetryService` for each incoming message, so a
   breach is acted on within the telemetry period (5 s), not the timer period.
2. **`ControlLoop.tick()`** — `@Scheduled(fixedDelayString = "${aqss.control.cycle-ms:60000}")`, the
   safety net for devices that stopped reporting.

```
OFF  and pm25 >= threshold                 → ACTIVATE level 3   (threshold_breach)
ON   and on_since >= 120 min               → DEACTIVATE         (max_on)
ON   and pm25 < 0.7 × threshold and >= 5m  → DEACTIVATE         (hysteresis_off)
NONE and running in AUTO                   → republish every 5 min (lease renewal)
```

Activation never waits. A breach means the air is already bad, and making the operator watch it get
worse for three minutes is a delay, not protection. The motor is protected on the OFF side instead:
minimum ON 5 min, maximum ON 2 h. A reading older than `stale-reading-minutes` is ignored entirely —
no command is safer than one based on data that stopped.

`decide()` is a pure static function over an `Inputs` record, so the whole decision table is unit
tested without Spring.

## 5. Pairing

A node identifies itself with `device_id` and a `pairing_code` (six symbols from the chip MAC,
displayed on the OLED). The backend registers the device the first time it hears from it, but leaves
it **unpaired** — `GET /devices` hides it.

`POST /devices/pair` takes the code and either a room code or a room name. A name that does not exist
creates the room, derives its code, and computes its threshold in the same request.

Unpairing is two-sided: the backend drops the binding, and `set_space` with an empty `space_id` tells
the node to clear its NVS copy and return to the pairing screen. Without that second half the node
keeps a room the operator already removed.

`DeviceService.ensure` deliberately ignores the `space_id` inside a telemetry payload: it is the
node's own stale copy, and letting it write the binding is what made a removed machine reappear on
the next message.

## 6. Configuration

Every value is an environment variable with a working default (`application.yml`).

| Variable | Default | Effect |
| :--- | :--- | :--- |
| `SERVER_PORT` | `PORT` or `8080` | HTTP port |
| `DB_URL` / `DB_USER` / `DB_PASS` | local postgres | JDBC |
| `MQTT_BROKER_URL` | `tcp://localhost:1883` | `ssl://host:8883` for a cloud broker |
| `MQTT_USER` / `MQTT_PASS` / `MQTT_QOS` | — | broker credentials |
| `CORS_ORIGIN` | `http://localhost:5173` | **comma-separated list** of dashboard origins |
| `CONTROL_CYCLE_MS` | `60000` | safety-net tick |
| `CONTROL_HYSTERESIS_RATIO` | `0.7` | OFF point as a fraction of the threshold |
| `CONTROL_MIN_ON_MINUTES` | `5` | motor protection |
| `CONTROL_MAX_ON_MINUTES` | `120` | motor protection |
| `CONTROL_STALE_READING_MINUTES` | `5` | a reading older than this drives nothing |
| `CONTROL_LEASE_RENEW_MINUTES` | `5` | how often a running fan's lease is renewed |
| `FLYWAY_ENABLED` | `true` | schema migration |

## 7. Removed

| Removed | Why |
| :--- | :--- |
| `AiClient`, `Predictions`, `PredictionRepository`, `/forecast`, `predictions` table | Nothing produced or consumed a forecast; PM2.5 at 5 s cadence is unpredictable beyond ~30 min |
| `AI_ENABLED`, `AI_BASE_URL`, `AI_TIMEOUT_MS` | No external service to call |
| `CONTROL_FORECAST_*`, `CONTROL_MIN_OFF_MINUTES` as an activation gate | Dead configuration once the forecast and the wait were gone |
| JWT / RBAC / device API keys | A device identifies itself; the dashboard is open on a private deployment |
| STOMP `/ws`, Spring Integration | The dashboard polls two REST endpoints |
| AQI / NowCast | Derived display metric nobody read; control uses raw PM2.5 |
