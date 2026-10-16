# Layer 2 — Backend System: Detailed Implementation Plan

> **Version:** 5.0 — The Brain (closed-loop control, Space-aware)
> **Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Mosquitto MQTT · STOMP WebSocket · Docker
> **Reference:** [aqss-concept-v2.md](./aqss-concept-v2.md) · [io_contract.md](./io_contract.md)
>
> **V2 changes:** Space is a first-class entity · `ControlDecisionService` is the decision brain · `ActuatorService` drives the filter fan via `set_actuator` · AQI is a **NowCast display-only** metric computed at read time · Redis **removed** (was never referenced) · partition management is actually scheduled · `model_evaluations` removed with the Autoencoder.

---

## 1. Architecture Overview

```
 ┌────────────────┐          ┌───────────────────────┐
 │ ESP32-S3 node  │ ───────► │ Eclipse Mosquitto     │
 │ (integrated)   │   MQTT   │ Broker (1883, auth)   │
 └────────────────┘          └───────────┬───────────┘
                                         │ telemetry / actuator/state / status
                       ┌─────────────────▼────────────────────────────┐
                       │ Spring Boot 3                                │
                       │  MqttInboundAdapter                          │
                       │        │                                     │
                       │        ├──► TelemetryIngestionService        │
                       │        │      ├── BlockingQueue ──► batch DB │
                       │        │      └── SimpMessagingTemplate      │
                       │        ├──► DeviceStatusService (LWT)        │
                       │        └──► ActuatorService (state tracking) │
                       │                                              │
                       │  ControlDecisionService  ←── @Scheduled 60 s │
                       │    ├─ ThresholdService (ML#1, cached)        │
                       │    ├─ DosageService (ML#2)                   │
                       │    ├─ ForecastService (DL, cached hourly)    │
                       │    └─► ActuatorService ──► MQTT set_actuator │
                       └───────┬──────────────────────┬───────────────┘
                               │                      │ STOMP
                        ┌──────▼───────┐      ┌───────▼────────┐
                        │ PostgreSQL 16│      │ React Frontend │
                        └──────┬───────┘      └────────────────┘
                               │ HTTP (RestClient)
                        ┌──────▼───────────────────────────┐
                        │ Layer 3: AI (threshold/dosage/   │
                        │            forecast)             │
                        └──────────────────────────────────┘
```

> **Decision authority lives here.** The node only measures, displays and actuates.

### Technology Selection Rationale

| Component | Choice | Why |
| :--- | :--- | :--- |
| **Language** | Java 21 LTS | Strong typing, mature ecosystem |
| **Framework** | Spring Boot 3.3 | Spring Integration MQTT, Security 6, STOMP built-in |
| **MQTT** | Spring Integration MQTT + Paho v3 | Auto-reconnect, QoS, wildcard topics, error channel |
| **WebSocket** | STOMP + SockJS | Per-topic pub/sub, JWT auth via interceptor |
| **Database** | PostgreSQL 16 | Range partitioning for time-series, mature indexing |
| **ORM** | Spring Data JPA + Hibernate 6 | `@EmbeddedId` composite key, native queries, projections |
| **Migrations** | Flyway | SQL-based, version-controlled |
| **Auth** | Spring Security 6 + JJWT 0.12 | Stateless JWT, dual filter chain, RBAC |
| **Mapping** | MapStruct | Compile-time DTO mapping |
| **AI calls** | Spring `RestClient` | Blocking, 30 s timeout — no extra dependency |

---

## 2. Database Schema (PostgreSQL 16)

### 2.1 Design Decisions

| Decision | Choice | Rationale |
| :--- | :--- | :--- |
| Table layout | **Wide table** (one row = one reading) | Sensors sampled simultaneously |
| Primary key | **Composite `(id, recorded_at)`** | Required by partitioning; JPA must map it with `@EmbeddedId` |
| Float type | `REAL` (4 bytes) | Sensor precision does not need 64-bit |
| Time column | `TIMESTAMPTZ` | `date_trunc()` support |
| AQI | **Not persisted** | Derived at read time (NowCast) — it is not a raw measurement |
| Partitioning | Range by `recorded_at` (monthly) | Lifecycle management, pruning |

### 2.2 `V1__init_schema.sql`

```sql
CREATE TABLE devices (
    device_id          VARCHAR(32) PRIMARY KEY,
    name               VARCHAR(100) NOT NULL,
    location_name      VARCHAR(150),
    latitude           DOUBLE PRECISION,
    longitude          DOUBLE PRECISION,
    api_key_hash       VARCHAR(64) NOT NULL,
    firmware_version   VARCHAR(20) DEFAULT '1.0.0',
    is_active          BOOLEAN DEFAULT TRUE,
    last_seen_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    updated_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE users (
    id                 BIGSERIAL PRIMARY KEY,
    email              VARCHAR(255) UNIQUE NOT NULL,
    password_hash      VARCHAR(255) NOT NULL,
    full_name          VARCHAR(100),
    role               VARCHAR(20) DEFAULT 'ROLE_USER'
                       CHECK (role IN ('ROLE_ADMIN','ROLE_USER','ROLE_VIEWER')),
    is_active          BOOLEAN DEFAULT TRUE,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

-- Raw telemetry only — no AQI columns (AQI is derived, see §9)
CREATE TABLE telemetry_data (
    id                 BIGSERIAL,
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    device_id          VARCHAR(32) NOT NULL REFERENCES devices(device_id),
    space_id           VARCHAR(20),
    pm25               REAL NOT NULL,
    co2                REAL NOT NULL,
    temperature        REAL NOT NULL,
    humidity           REAL NOT NULL,
    wifi_rssi          SMALLINT,
    uptime_sec         INT,
    free_heap          INT,
    min_free_heap      INT,
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE UNIQUE INDEX uq_telemetry_device_time ON telemetry_data (device_id, recorded_at);
CREATE INDEX idx_telemetry_device_time_desc ON telemetry_data (device_id, recorded_at DESC);

-- Initial partitions (always extend to current month + 2 ahead)
CREATE TABLE telemetry_data_2026_09 PARTITION OF telemetry_data FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE telemetry_data_2026_10 PARTITION OF telemetry_data FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE telemetry_data_2026_11 PARTITION OF telemetry_data FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE TABLE alerts (
    id                 BIGSERIAL PRIMARY KEY,
    space_id           BIGINT,
    device_id          VARCHAR(32) REFERENCES devices(device_id),
    alert_type         VARCHAR(30) NOT NULL,   -- PM25_THRESHOLD | CO2_ALERT | SENSOR_OFFLINE | SENSOR_SATURATED
    severity           VARCHAR(10) NOT NULL,
    message            TEXT,
    triggered_at       TIMESTAMPTZ DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ
);
CREATE INDEX idx_alerts_active ON alerts (device_id) WHERE resolved_at IS NULL;

-- Quantile forecast (`model_evaluations` table REMOVED in V2 with the Autoencoder)
CREATE TABLE predictions (
    id                 BIGSERIAL PRIMARY KEY,
    space_id           BIGINT,
    predicted_at       TIMESTAMPTZ DEFAULT NOW(),
    forecast_data      JSONB NOT NULL,   -- [{target_time, pm25_q50, pm25_q10, pm25_q90}] × 96
    model_version      VARCHAR(20) NOT NULL
);
```

> **Upgrading an existing V1 database:** add `ALTER TABLE telemetry_data DROP COLUMN aqi_index, DROP COLUMN aqi_level, ADD COLUMN space_id VARCHAR(20), ADD COLUMN min_free_heap INT;` to the V4 migration instead of editing V1.

### 2.3 `V4__spaces_and_actuation.sql` (NEW)

```sql
-- ─── Space registry ────────────────────────────────────────────────
CREATE TABLE spaces (
    id                   BIGSERIAL PRIMARY KEY,
    code                 VARCHAR(20) UNIQUE NOT NULL,   -- device-facing id (e.g. 'SP_201'); telemetry.space_id and MQTT set_space carry this
    name                 VARCHAR(100) NOT NULL,
    area_m2              REAL,
    ceiling_m            REAL,
    volume_m3            REAL NOT NULL,
    purpose              VARCHAR(30) NOT NULL,      -- bedroom|dorm|classroom|office
    region               VARCHAR(50) NOT NULL,      -- e.g. hanoi_caugiay
    ventilation_estimate VARCHAR(20) DEFAULT 'low', -- low|medium|high
    created_at           TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Node ↔ space mapping ──────────────────────────────────────────
CREATE TABLE space_devices (
    space_id   BIGINT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    device_id  VARCHAR(32) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL DEFAULT 'SENSOR',  -- SENSOR|ACTUATOR|COMBINED
    PRIMARY KEY (space_id, device_id)
);

-- ─── Cached ML#1 / ML#2 output ─────────────────────────────────────
CREATE TABLE space_thresholds (
    space_id             BIGINT PRIMARY KEY REFERENCES spaces(id) ON DELETE CASCADE,
    base_threshold_pm25  REAL NOT NULL,
    uncertainty_lower    REAL,
    uncertainty_upper    REAL,
    dosage_level         SMALLINT,
    dosage_duration_min  INT,
    model_version        VARCHAR(20),
    computed_at          TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Actuator registry ─────────────────────────────────────────────
CREATE TABLE actuators (
    device_id        VARCHAR(32) PRIMARY KEY REFERENCES devices(device_id),
    space_id         BIGINT REFERENCES spaces(id),
    current_state    VARCHAR(10) DEFAULT 'OFF',   -- ON|OFF
    current_level    SMALLINT DEFAULT 0,          -- 0-3
    mode             VARCHAR(10) DEFAULT 'AUTO',  -- AUTO|MANUAL
    duty_pct         SMALLINT DEFAULT 0,          -- actual PWM duty (0/35/60/90 % per level)
    filter_runtime_h REAL DEFAULT 0,              -- cumulative runtime (filter-life tracking)
    last_command_at  TIMESTAMPTZ,
    state_since      TIMESTAMPTZ DEFAULT NOW()    -- drives the min ON / min OFF safety timers
);

-- ─── Decision audit log ────────────────────────────────────────────
CREATE TABLE control_decisions (
    id                  BIGSERIAL PRIMARY KEY,
    space_id            BIGINT REFERENCES spaces(id),
    decided_at          TIMESTAMPTZ DEFAULT NOW(),
    action              VARCHAR(20) NOT NULL,     -- ACTIVATE|DEACTIVATE|ALERT|IDLE
    mode                VARCHAR(10) NOT NULL,     -- AUTO|MANUAL|ALERT
    trigger_reason      VARCHAR(40),              -- threshold_breach|forecast_breach_q90|manual|hysteresis_off
    pm25_at_decision    REAL,
    threshold_used      REAL,
    dosage_level        SMALLINT,
    dosage_duration_min INT
);
CREATE INDEX idx_decisions_space_time ON control_decisions (space_id, decided_at DESC);

-- ─── Upgrade telemetry_data on a V1 deployment (skip on fresh DB) ──
-- ALTER TABLE telemetry_data DROP COLUMN IF EXISTS aqi_index;
-- ALTER TABLE telemetry_data DROP COLUMN IF EXISTS aqi_level;
-- ALTER TABLE telemetry_data ADD COLUMN IF NOT EXISTS space_id VARCHAR(20);
-- ALTER TABLE telemetry_data ADD COLUMN IF NOT EXISTS min_free_heap INT;
```

### 2.4 Partition Management (`V2__partition_management.sql`) — **actually scheduled**

```sql
CREATE OR REPLACE FUNCTION create_monthly_partition()
RETURNS void AS $$
DECLARE
    partition_date DATE;
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    partition_date := date_trunc('month', NOW() + INTERVAL '1 month');
    partition_name := 'telemetry_data_' || to_char(partition_date, 'YYYY_MM');

    -- V1 BUG: checked relname without the schema → possible false match
    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = partition_name
          AND relnamespace = 'public'::regnamespace
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF telemetry_data FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            date_trunc('month', NOW() + INTERVAL '1 month'),
            date_trunc('month', NOW() + INTERVAL '2 month'));
        RAISE NOTICE 'Created partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;
```

**The function must be invoked** — V1 defined it and never called it, so inserts would fail from 2027-01:

```java
@Service @RequiredArgsConstructor @Slf4j
public class PartitionManagerService {

    private final EntityManager em;

    @PostConstruct                                   // cover the current month at boot
    @Scheduled(cron = "0 0 3 25 * *")                // 25th of every month, 03:00
    public void createNextPartition() {
        em.createNativeQuery("SELECT create_monthly_partition()").getSingleResult();
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void refreshHourlyView() {
        em.createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY telemetry_hourly").executeUpdate();
    }

    @Scheduled(cron = "0 5 * * * *")
    public void refreshDailyView() {
        em.createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY telemetry_daily").executeUpdate();
    }

    /** Drop WHOLE expired months. V1 derived a partition name from `now − 90 days`
     *  and dropped it — that can remove a partition still holding newer data. */
    @Scheduled(cron = "0 0 2 1 * *")
    @SuppressWarnings("unchecked")
    public void dropExpiredPartitions() {
        List<String> names = em.createNativeQuery("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE p.relname = 'telemetry_data'
                  AND c.relname ~ '^telemetry_data_\\d{4}_\\d{2}$'
                  AND c.relname < 'telemetry_data_'
                      || to_char(date_trunc('month', NOW() - INTERVAL '90 days'), 'YYYY_MM')
                """).getResultList();

        for (Object n : names) {
            String name = n.toString();
            em.createNativeQuery("DROP TABLE IF EXISTS " + name).executeUpdate();
            log.info("Dropped expired partition: {}", name);
        }
    }
}
```

### 2.5 Materialized Views (`V3__materialized_views.sql`)

Unchanged from V1 (`telemetry_hourly`, `telemetry_daily` with avg/min/max per metric + `sample_count`), **minus** the `avg_aqi` / `max_aqi` columns (AQI is no longer stored). Unique index `(device_id, bucket DESC)` supports `REFRESH ... CONCURRENTLY`.

---

## 3. MQTT Ingestion Pipeline

- **Spring Integration MQTT** with `MqttPahoMessageDrivenChannelAdapter`, `automaticReconnect=true`, `recoveryInterval=5000ms`.
- Wildcard subscriptions: `aqss/devices/+/telemetry`, `aqss/devices/+/status`, `aqss/devices/+/actuator/state`.
- Error channel routes malformed JSON to a dead-letter log instead of crashing the flow.

```
MQTT msg → MqttPahoAdapter → IntegrationFlow → handler
   ├─ telemetry      → validate ranges → BlockingQueue(10,000) ──► @Scheduled(1s) saveAll(batch)
   │                                             └──────────────► STOMP /topic/devices/{id}  (<5 ms)
   ├─ status         → DeviceStatusService (last_seen_at, ONLINE/OFFLINE, alert SENSOR_OFFLINE)
   └─ actuator/state → ActuatorService (update actuators row, STOMP /topic/actuators/{id})
```

- `reWriteBatchedInserts=true` + `hibernate.jdbc.batch_size=100` + `order_inserts=true`.
- `@PreDestroy` flushes the queue on graceful shutdown.

---

## 4. The Brain — Control Decision Flow

```java
@Scheduled(fixedDelayString = "${aqss.control.cycle-ms:60000}")   // decision cycle: 60 s
public void evaluateSpaces() {
    for (Space s : spaceService.findActiveSpaces()) {
        SpaceThreshold th = thresholdService.getOrCompute(s);   // cached ML#1
        Forecast fc       = forecastService.latestFor(s);       // cached DL (hourly)
        double pm25       = telemetryService.latestPm25For(s);  // rolling 5-min median

        // 1. Hard breach
        if (pm25 >= th.baseThreshold()) {
            Dosage d = dosageService.compute(s, pm25, th);      // ML#2
            actuatorService.activate(s, d);                     // publish set_actuator ON level=d.level
            audit(s, "ACTIVATE", "AUTO", "threshold_breach", pm25, th, d);
            continue;
        }
        // 2. Early warning from the q90 (worst-case) forecast
        Double hoursToBreach = fc.hoursUntilQ90Breach(th.baseThreshold());
        if (hoursToBreach != null && hoursToBreach <= 2.0) {
            actuatorService.warn(s, hoursToBreach);             // publish display_warning
            audit(s, "ALERT", "ALERT", "forecast_breach_q90", pm25, th, null);
            continue;
        }
        // 3. Hysteresis release at 70% of threshold
        if (actuatorService.isOn(s) && pm25 < th.baseThreshold() * 0.7) {
            actuatorService.deactivate(s);
            audit(s, "DEACTIVATE", "AUTO", "hysteresis_off", pm25, th, null);
        }
    }
}
```

**Safety constraints enforced by `ActuatorService`:**

| Constraint | Value |
| :--- | :--- |
| Minimum ON time | 5 min |
| Minimum OFF time | 3 min |
| Maximum continuous ON | 120 min |
| Hysteresis band | 70 % of threshold |
| Manual override | Always wins (dashboard **and** physical button) |
| Fail-safe | Node turns the fan OFF after 10 min without a command |

**CO2 is alert-only** — `CO2_ALERT` never triggers actuation (HEPA cannot remove CO2).

---

## 5. REST API Endpoints

### 5.1 Auth (unchanged)
`POST /api/v1/auth/register` · `POST /api/v1/auth/token` · `POST /api/v1/auth/refresh`

### 5.2 Spaces & Actuation (NEW)

| Endpoint | Method | Auth | Description |
| :--- | :---: | :---: | :--- |
| `GET /api/v1/spaces` | GET | JWT | List spaces with derived threshold |
| `POST /api/v1/spaces` | POST | ADMIN | Create space (triggers ML#1) |
| `GET /api/v1/spaces/{id}` | GET | JWT | Space + threshold + dosage + devices |
| `PUT /api/v1/spaces/{id}` | PUT | ADMIN | Update → invalidates `space_thresholds` cache |
| `DELETE /api/v1/spaces/{id}` | DELETE | ADMIN | Delete |
| `POST /api/v1/spaces/{id}/devices` | POST | ADMIN | Assign node (`{device_id, role}`) |
| `GET /api/v1/spaces/{id}/decisions` | GET | JWT | Decision timeline (audit log) |
| `POST /api/v1/spaces/{id}/actuator` | POST | JWT | Manual control `{mode, state, level}` |
| `POST /api/v1/spaces/{id}/threshold` | POST | ADMIN | Force ML#1 recompute |
| `GET /api/v1/actuators` | GET | JWT | Actuator registry + live state |

Payload shapes: `io_contract.md` §6.

### 5.3 Telemetry / Forecast / Alerts

| Endpoint | Method | Auth | Description |
| :--- | :---: | :---: | :--- |
| `GET /api/v1/telemetry/latest` | GET | JWT | Latest reading + derived AQI + `co2_alert` |
| `GET /api/v1/telemetry/history` | GET | JWT | Raw range query |
| `GET /api/v1/telemetry/aggregate` | GET | JWT | `date_trunc` aggregate |
| `GET /api/v1/telemetry/hourly` / `daily` | GET | JWT | Materialized views |
| `GET /api/v1/forecast/{space_id}` | GET | JWT | 96-point quantile forecast |
| `POST /api/v1/forecast/run` | POST | ADMIN | Trigger forecast now |
| `GET /api/v1/alerts` / `PUT /{id}/resolve` | GET/PUT | JWT | Alerts |
| `GET /actuator/health` | GET | — | Liveness |

---

## 6. WebSocket (STOMP)

| Destination | Description |
| :--- | :--- |
| `/topic/devices/{id}` | Live telemetry |
| `/topic/spaces/{id}/decision` | Control decision stream (NEW) |
| `/topic/actuators/{id}` | Fan state updates (NEW) |
| `/topic/alerts` | Alerts |
| `/topic/device-status` | Online/offline (from LWT) |

JWT validated on the STOMP `CONNECT` frame via `ChannelInterceptor` (`Authorization: Bearer`).

---

## 7. Security (unchanged from V1, plus CORS)

- Dual filter chain: `X-API-Key` (device, SHA-256 hash lookup) and `Bearer <JWT>` (users).
- BCrypt passwords · JJWT 0.12 HS256 · 30-min access / 7-day refresh · `ROLE_ADMIN/USER/VIEWER/DEVICE` · stateless sessions.
- **CORS (V1 omission):** allow the Vite dev origin:

```java
@Bean CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of("http://localhost:5173", corsOrigin));
    c.setAllowedMethods(List.of("GET","POST","PUT","DELETE"));
    c.setAllowedHeaders(List.of("Authorization","Content-Type"));
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/**", c);
    return src;
}
```

---

## 8. JPA Entity Design

```java
@Entity @Table(name = "telemetry_data")
public class TelemetryData {

    @EmbeddedId
    private TelemetryDataId id;   // (Long id, Instant recordedAt) — composite.
                                  // REQUIRED: the partitioned table's PK is (id, recorded_at).
                                  // V1 used @Id on id + IDENTITY → ddl-auto: validate failed.

    @Column(name = "device_id", nullable = false, length = 32)
    private String deviceId;

    @Column(name = "space_id", length = 20)
    private String spaceId;

    @Column(nullable = false) private Float pm25;
    @Column(nullable = false) private Float co2;
    @Column(nullable = false) private Float temperature;
    @Column(nullable = false) private Float humidity;
    @Column(name = "wifi_rssi")     private Short wifiRssi;
    @Column(name = "uptime_sec")    private Integer uptimeSec;
    @Column(name = "free_heap")     private Integer freeHeap;
    @Column(name = "min_free_heap") private Integer minFreeHeap;
}
```

> Do **not** use `@GeneratedValue(IDENTITY)` on a partitioned table with a composite PK — Hibernate cannot populate the composite key and validation fails at startup.

New entities: `Space`, `SpaceDevice`, `SpaceThreshold`, `Actuator`, `ControlDecision` (mapped 1:1 to the V4 tables).

---

## 9. AQI — NowCast, Display Only (NEW service)

```java
/** AQI is derived, never stored. Computed from raw PM2.5 with the EPA NowCast
 *  algorithm (weighted average over the last 12 hours). Display only — the
 *  control loop compares raw PM2.5 against the space threshold. */
@Service
public class NowCastService {
    public int computeAqi(List<TelemetryData> last12h) { /* EPA NowCast weights */ }
    public String aqiLevel(int aqi) { /* breakpoint table */ }
}
```

V1's `AqiCalculatorService` ran on the device against 24h-average breakpoints applied to *instantaneous* readings — methodologically wrong. Removed.

---

## 10. Project Structure

```
backend/
├── src/main/java/com/aqss/backend/
│   ├── BackendApplication.java
│   ├── config/            MqttIntegrationConfig · WebSocketBrokerConfig · SecurityConfig · CorsConfig
│   ├── controller/        AuthController · DeviceController · TelemetryController
│   │                      SpaceController · ActuatorController · ForecastController · AlertController
│   ├── dto/               auth/ · device/ · telemetry/ · space/
│   ├── entity/            Device · User · TelemetryData · Space · SpaceDevice
│   │                      SpaceThreshold · Actuator · ControlDecision · Alert · Prediction
│   ├── mqtt/              MqttTelemetryHandler · MqttStatusHandler · MqttCommandPublisher
│   ├── repository/        8 repositories + projection/TelemetryAggregateProjection
│   ├── security/          filter/JwtAuthenticationFilter · filter/DeviceApiKeyAuthFilter
│   │                      jwt/JwtService · websocket/StompAuthChannelInterceptor
│   └── service/           AuthService · DeviceService · DeviceStatusService
│                          TelemetryIngestionService · NowCastService · AlertService
│                          SpaceService · ThresholdService · DosageService
│                          ForecastService · ControlDecisionService · ActuatorService
│                          PartitionManagerService
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/      V1__init_schema.sql · V2__partition_management.sql
│                          V3__materialized_views.sql · V4__spaces_and_actuation.sql
├── mosquitto/config/mosquitto.conf
├── pom.xml                (unchanged from V1 — no new dependency required)
├── Dockerfile
└── docker-compose.yml
```

---

## 11. Configuration (`application.yml`)

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  application:
    name: aqss-backend
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:aqss_db}?reWriteBatchedInserts=true
    username: ${DB_USER:aqss_user}
    password: ${DB_PASS:aqss_password}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc.batch_size: 100        # dialect auto-detected in Hibernate 6 — removed
        order_inserts: true
  flyway:
    enabled: true
    baseline-on-migrate: true

mqtt:
  broker-url: tcp://${MQTT_HOST:localhost}:1883
  client-id: aqss-backend-server
  username: ${MQTT_USER:aqss_device}   # must match mosquitto.conf — V1 defaulted to empty
  password: ${MQTT_PASS:aqss_password}
  topics:
    telemetry: "aqss/devices/+/telemetry"
    status: "aqss/devices/+/status"
    actuator-state: "aqss/devices/+/actuator/state"

security:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-change-me-0123456789abcdef}   # dev default added
    access-token-expiration-ms: 1800000
    refresh-token-expiration-ms: 604800000

app:
  cors:
    origin: ${CORS_ORIGIN:http://localhost:5173}

aqss:
  ai:
    base-url: http://${AI_HOST:localhost}:8000
    timeout-ms: 30000
  control:
    cycle-ms: 60000                 # decision cycle
    forecast-cron: "0 0 * * * *"    # hourly DL refresh
    hysteresis-ratio: 0.7
    min-on-minutes: 5
    min-off-minutes: 3
    max-on-minutes: 120
  telemetry:
    batch-flush-interval-ms: 1000
```

`mosquitto.conf` (auth must match):

```
listener 1883
allow_anonymous false
password_file /mosquitto/config/pwfile
```

---

## 12. Docker Compose (Redis removed)

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: aqss_user
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: aqss_db
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U aqss_user -d aqss_db"]
      interval: 5s

  mosquitto:
    image: eclipse-mosquitto:2
    ports: ["1883:1883"]
    volumes:
      - ./mosquitto/config/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro
      - ./mosquitto/config/pwfile:/mosquitto/config/pwfile:ro

  backend:
    build: .
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      DB_HOST: postgres
      MQTT_HOST: mosquitto
      AI_HOST: layer3-ai
      JWT_SECRET: ${JWT_SECRET}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports: ["8080:8080"]

  layer3-ai:
    build: ../layer3_dl
    ports: ["8000:8000"]

volumes:
  pgdata:
```

> **Redis removed** — declared in V1 but referenced by no service. Port 9001 (MQTT websocket) removed as well (unused). If caching is needed later, prefer Spring Cache + Caffeine in-process.

---

## 13. Key Query Patterns

```sql
-- Latest reading per device (uses idx_telemetry_device_time_desc)
SELECT * FROM telemetry_data
WHERE device_id = 'AQSS_ESP32S3_01'
ORDER BY recorded_at DESC LIMIT 1;

-- Latest reading per ALL devices
SELECT DISTINCT ON (device_id) *
FROM telemetry_data
WHERE recorded_at >= NOW() - INTERVAL '1 hour'
ORDER BY device_id, recorded_at DESC;

-- 24h history for a chart
SELECT recorded_at, pm25, co2, temperature, humidity
FROM telemetry_data
WHERE space_id = 'SP_201'
  AND recorded_at >= NOW() - INTERVAL '24 hours'
ORDER BY recorded_at ASC;

-- 15-min median series for the DL forecast request (96 steps)
SELECT (floor(extract(epoch FROM recorded_at) / 900) * 900) AS bucket,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY pm25) AS pm25
FROM telemetry_data
WHERE space_id = 'SP_201' AND recorded_at >= NOW() - INTERVAL '24 hours'
GROUP BY bucket ORDER BY bucket ASC;

-- Cursor pagination (no OFFSET)
SELECT * FROM telemetry_data
WHERE device_id = 'AQSS_ESP32S3_01' AND recorded_at < :cursor
ORDER BY recorded_at DESC LIMIT 50;
```

> `device_id` examples use the contract pattern `AQSS_ESP32S3_XX` everywhere (V1 mixed `ESP32S3_AQSS_01`).

---

## 14. Performance Targets

| Metric | Target |
| :--- | :--- |
| MQTT → DB latency | < 1 s (batch flush) |
| MQTT → STOMP WebSocket | < 5 ms |
| REST API p95 | < 100 ms |
| Decision cycle worst case | < 60 s |
| Concurrent MQTT devices | 100+ |
| Concurrent WebSocket clients | 500+ |

---

## 15. Verification Plan

```bash
./mvnw test     # unit + integration (Testcontainers PostgreSQL 16)
```

Manual:
1. `docker-compose up -d` — all services healthy.
2. Flyway applies V1→V4 — tables, partitions, matviews, spaces.
3. `mosquitto_pub` a telemetry JSON → row in DB + STOMP broadcast.
4. Kill the node → `status` topic emits `OFFLINE` (LWT).
5. Insert a PM2.5 reading above a space's threshold → a `control_decisions` `ACTIVATE` row is written and the node receives `set_actuator`.
6. Confirm `PartitionManagerService` created next month's partition.
7. Confirm CORS: the Vite dev origin can call `/api/v1/...` from a browser.
