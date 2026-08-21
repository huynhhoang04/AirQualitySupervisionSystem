# Layer 2 — Backend System: Detailed Implementation Plan

> **Version:** 4.0 — Java Spring Boot + PostgreSQL
> **Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Mosquitto MQTT · STOMP WebSocket · Redis · Docker

---

## 1. Architecture Overview

```
 ┌────────────────┐          ┌───────────────────────┐
 │ ESP32-S3 IoT   │ ───────► │ Eclipse Mosquitto     │
 │ Devices        │   MQTT   │ MQTT Broker (1883)    │
 └────────────────┘          └───────────┬───────────┘
                                         │
                      ┌──────────────────┼──────────────────────────┐
                      │ Spring Boot 3 Application                   │
                      │                  │                          │
                      │     ┌────────────▼────────────┐             │
                      │     │ Spring Integration MQTT │             │
                      │     │ (MqttPahoMessageDriven  │             │
                      │     │  ChannelAdapter)         │             │
                      │     └────────────┬────────────┘             │
                      │                  │ Async Event              │
                      │     ┌────────────▼────────────┐             │
                      │     │ TelemetryIngestionService│             │
                      │     └───────┬──────────┬──────┘             │
                      │             │          │                    │
                      │             ▼          ▼                    │
                      │     ┌────────────┐ ┌──────────────────┐     │
                      │     │ BlockingQ  │ │ SimpMessaging    │     │
                      │     │ Batch Buf  │ │ Template (STOMP) │     │
                      │     └─────┬──────┘ └────────┬─────────┘     │
                      └───────────┼─────────────────┼───────────────┘
                                  │ @Scheduled      │
                                  │ flush 1s        │ STOMP frames
                                  ▼                 ▼
                      ┌──────────────────┐  ┌───────────────────┐
                      │ PostgreSQL 16    │  │ React Dashboard   │
                      │                  │  │ SockJS + STOMP    │
                      └──────────────────┘  └───────────────────┘
                                  │
                         Internal HTTP call
                                  ▼
                      ┌──────────────────┐
                      │ Layer 3: DL      │
                      │ Inference API    │
                      └──────────────────┘
```

### Technology Selection Rationale

| Component | Choice | Why |
| :--- | :--- | :--- |
| **Language** | Java 21 LTS | Strong typing, mature ecosystem, `java-springboot-pro` agent |
| **Framework** | Spring Boot 3.3 | Spring Integration MQTT, Spring Security 6, STOMP WebSocket built-in |
| **MQTT** | Spring Integration MQTT + Eclipse Paho v3 | Auto-reconnect, QoS, wildcard topics, error channel |
| **WebSocket** | STOMP over WebSocket + SockJS | Per-topic pub/sub (`/topic/devices/{id}`), auth via interceptor |
| **Database** | PostgreSQL 16 | Industry standard RDBMS, range partitioning for time-series, mature indexing |
| **ORM** | Spring Data JPA + Hibernate 6 | `@EmbeddedId` composite key, native `@Query`, type-safe projections |
| **Migrations** | Flyway | SQL-based, version-controlled |
| **Auth** | Spring Security 6 + JJWT 0.12 | Stateless JWT, dual filter chain (Device API Key + User Bearer), RBAC |
| **Mapping** | MapStruct | Compile-time DTO ↔ Entity mapping, zero reflection |
| **Build** | Maven (pom.xml) | Standard Java build tool, extensive plugin ecosystem |
| **Container** | Docker Compose | Single `docker-compose up` for all services |

---

## 2. Database Schema (PostgreSQL 16)

### 2.1 Design Decisions

| Decision | Choice | Rationale |
| :--- | :--- | :--- |
| Table layout | **Wide table** (one row = one reading with all metrics) | Sensors sampled simultaneously; avoids 5× row explosion |
| Primary key | `id BIGSERIAL` + composite unique on `(device_id, recorded_at)` | Standard PG pattern, simple JPA mapping, efficient FK references |
| Float type | `REAL` (4 bytes) | 50% savings vs `DOUBLE PRECISION`; sensor precision doesn't need 64-bit |
| Time column | `TIMESTAMPTZ` | Timezone-aware, standard `date_trunc()` support |
| AQI | `SMALLINT` (2 bytes) | Range 0–500 fits int2 |
| Partitioning | Range partition by `recorded_at` (monthly) | Efficient data lifecycle, fast range queries, partition pruning |

### 2.2 Flyway Migration (`V1__init_schema.sql`)

```sql
-- ─── Device Registry ───────────────────────────────────────────────
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

-- ─── Users (RBAC) ─────────────────────────────────────────────────
CREATE TABLE users (
    id                 BIGSERIAL PRIMARY KEY,
    email              VARCHAR(255) UNIQUE NOT NULL,
    password_hash      VARCHAR(255) NOT NULL,
    full_name          VARCHAR(100),
    role               VARCHAR(20) DEFAULT 'ROLE_USER'
                       CHECK (role IN ('ROLE_ADMIN', 'ROLE_USER', 'ROLE_VIEWER')),
    is_active          BOOLEAN DEFAULT TRUE,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Telemetry Data (Partitioned by Month) ─────────────────────────
CREATE TABLE telemetry_data (
    id                 BIGSERIAL,
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    device_id          VARCHAR(32) NOT NULL REFERENCES devices(device_id),
    pm25               REAL NOT NULL,
    co2                REAL NOT NULL,
    temperature        REAL NOT NULL,
    humidity           REAL NOT NULL,
    aqi_index          SMALLINT NOT NULL,
    aqi_level          VARCHAR(30) NOT NULL,
    wifi_rssi          SMALLINT,
    uptime_sec         INT,
    free_heap          INT,
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);

-- Unique constraint for deduplication
CREATE UNIQUE INDEX uq_telemetry_device_time
    ON telemetry_data (device_id, recorded_at);

-- Index for per-device time-range queries
CREATE INDEX idx_telemetry_device_time_desc
    ON telemetry_data (device_id, recorded_at DESC);

-- ─── Create initial monthly partitions ─────────────────────────────
CREATE TABLE telemetry_data_2026_08 PARTITION OF telemetry_data
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE telemetry_data_2026_09 PARTITION OF telemetry_data
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE telemetry_data_2026_10 PARTITION OF telemetry_data
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE telemetry_data_2026_11 PARTITION OF telemetry_data
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE telemetry_data_2026_12 PARTITION OF telemetry_data
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

-- ─── Alerts ────────────────────────────────────────────────────────
CREATE TABLE alerts (
    id                 BIGSERIAL PRIMARY KEY,
    device_id          VARCHAR(32) REFERENCES devices(device_id),
    alert_type         VARCHAR(30) NOT NULL,
    severity           VARCHAR(10) NOT NULL,
    message            TEXT,
    triggered_at       TIMESTAMPTZ DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ
);

CREATE INDEX idx_alerts_active ON alerts (device_id)
    WHERE resolved_at IS NULL;

-- ─── AI Predictions & Evaluations ──────────────────────────────────
CREATE TABLE model_evaluations (
    id                 BIGSERIAL PRIMARY KEY,
    device_id          VARCHAR(32) REFERENCES devices(device_id),
    evaluated_at       TIMESTAMPTZ DEFAULT NOW(),
    is_sensor_anomaly  BOOLEAN NOT NULL,
    reconstruction_error REAL NOT NULL,
    assessed_aqi       SMALLINT NOT NULL,
    reliability_score  REAL NOT NULL
);

CREATE TABLE predictions (
    id                 BIGSERIAL PRIMARY KEY,
    device_id          VARCHAR(32) REFERENCES devices(device_id),
    predicted_at       TIMESTAMPTZ DEFAULT NOW(),
    forecast_data      JSONB NOT NULL, -- Array of {target_time, pm25, conf_upper, conf_lower}
    model_version      VARCHAR(20) NOT NULL
);
```

### 2.3 Auto-Create Monthly Partitions (`V2__partition_management.sql`)

```sql
-- Function to auto-create next month's partition
CREATE OR REPLACE FUNCTION create_monthly_partition()
RETURNS void AS $$
DECLARE
    partition_date DATE;
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    -- Create partition for next month
    partition_date := date_trunc('month', NOW() + INTERVAL '1 month');
    partition_name := 'telemetry_data_' || to_char(partition_date, 'YYYY_MM');
    start_date := partition_date;
    end_date := partition_date + INTERVAL '1 month';

    -- Check if partition already exists
    IF NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF telemetry_data FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );
        RAISE NOTICE 'Created partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;
```

### 2.4 Materialized Views for Aggregation (`V3__materialized_views.sql`)

```sql
-- ─── Hourly Aggregation ───────────────────────────────────────────
CREATE MATERIALIZED VIEW telemetry_hourly AS
SELECT
    date_trunc('hour', recorded_at) AS bucket,
    device_id,
    AVG(pm25)::REAL AS avg_pm25, MIN(pm25)::REAL AS min_pm25, MAX(pm25)::REAL AS max_pm25,
    AVG(co2)::REAL  AS avg_co2,  MIN(co2)::REAL  AS min_co2,  MAX(co2)::REAL  AS max_co2,
    AVG(temperature)::REAL AS avg_temp,
    AVG(humidity)::REAL AS avg_hum,
    AVG(aqi_index)::SMALLINT AS avg_aqi,
    MAX(aqi_index)::SMALLINT AS max_aqi,
    COUNT(*) AS sample_count
FROM telemetry_data
GROUP BY bucket, device_id;

CREATE UNIQUE INDEX idx_hourly_device_bucket
    ON telemetry_hourly (device_id, bucket DESC);

-- ─── Daily Aggregation ────────────────────────────────────────────
CREATE MATERIALIZED VIEW telemetry_daily AS
SELECT
    date_trunc('day', recorded_at) AS bucket,
    device_id,
    AVG(pm25)::REAL AS avg_pm25, MIN(pm25)::REAL AS min_pm25, MAX(pm25)::REAL AS max_pm25,
    AVG(co2)::REAL  AS avg_co2,  MIN(co2)::REAL  AS min_co2,  MAX(co2)::REAL  AS max_co2,
    AVG(temperature)::REAL AS avg_temp,
    AVG(humidity)::REAL AS avg_hum,
    AVG(aqi_index)::SMALLINT AS avg_aqi,
    MAX(aqi_index)::SMALLINT AS max_aqi,
    COUNT(*) AS sample_count
FROM telemetry_data
GROUP BY bucket, device_id;

CREATE UNIQUE INDEX idx_daily_device_bucket
    ON telemetry_daily (device_id, bucket DESC);
```

Materialized views are refreshed periodically via a Spring `@Scheduled` task:

```java
@Scheduled(cron = "0 */15 * * * *") // Every 15 minutes
public void refreshHourlyView() {
    entityManager.createNativeQuery(
        "REFRESH MATERIALIZED VIEW CONCURRENTLY telemetry_hourly"
    ).executeUpdate();
}

@Scheduled(cron = "0 5 * * * *") // Every hour at :05
public void refreshDailyView() {
    entityManager.createNativeQuery(
        "REFRESH MATERIALIZED VIEW CONCURRENTLY telemetry_daily"
    ).executeUpdate();
}
```

### 2.5 Data Retention

Old partitions are dropped via a scheduled Spring task or pg_cron:

```java
@Scheduled(cron = "0 0 2 1 * *") // 1st of every month at 02:00
public void dropOldPartitions() {
    // Drop partitions older than 90 days
    String partitionName = "telemetry_data_" +
        LocalDate.now().minusDays(90).format(DateTimeFormatter.ofPattern("yyyy_MM"));
    try {
        entityManager.createNativeQuery("DROP TABLE IF EXISTS " + partitionName).executeUpdate();
        log.info("Dropped old partition: {}", partitionName);
    } catch (Exception e) {
        log.warn("Partition {} does not exist or already dropped", partitionName);
    }
}
```

---

## 3. MQTT Ingestion Pipeline

### 3.1 Integration Pattern

- **Spring Integration MQTT** with `MqttPahoMessageDrivenChannelAdapter`
- Eclipse Paho v3 client with `automaticReconnect=true`, `recoveryInterval=5000ms`
- Wildcard subscription: `aqss/devices/+/telemetry`, `aqss/devices/+/status`
- Error channel for malformed messages

### 3.2 Ingestion Flow

```
MQTT Message → MqttPahoAdapter → IntegrationFlow → MqttTelemetryHandler
                                                            │
                                        ┌───────────────────┴───────────────────┐
                                        ▼                                       ▼
                               BlockingQueue<TelemetryData>          SimpMessagingTemplate
                               (capacity 10,000)                     .convertAndSend()
                                        │                                       │
                                @Scheduled(1000ms)                    /topic/telemetry
                                        │                            /topic/devices/{id}
                                        ▼
                               repository.saveAll(batch)
                               (Hibernate batch_size=100,
                                reWriteBatchedInserts=true)
```

### 3.3 Key Design Points

- **Batch buffer**: `LinkedBlockingQueue` drained every 1s or 500 records via `@Scheduled`
- **Hibernate batch optimization**: `reWriteBatchedInserts=true` in JDBC URL → 10× faster PG batch writes
- **WebSocket bridge**: `SimpMessagingTemplate.convertAndSend()` called immediately on MQTT receive (< 5ms)
- **Graceful shutdown**: `@PreDestroy` flushes remaining buffer items

### 3.4 MQTT Topics

| Topic Pattern | Direction | Purpose |
| :--- | :---: | :--- |
| `aqss/devices/{id}/telemetry` | Device → Server | Sensor readings JSON |
| `aqss/devices/{id}/status` | Device → Server | Heartbeat / online-offline |
| `aqss/devices/{id}/command` | Server → Device | Remote commands |

---

## 4. REST API Endpoints

### 4.1 Authentication

| Endpoint | Method | Auth | Description |
| :--- | :---: | :---: | :--- |
| `/api/v1/auth/register` | POST | — | Create user account |
| `/api/v1/auth/token` | POST | — | Login → JWT access + refresh tokens |
| `/api/v1/auth/refresh` | POST | Refresh | Rotate access token |

### 4.2 Devices

| Endpoint | Method | Auth | Description |
| :--- | :---: | :---: | :--- |
| `GET /api/v1/devices` | GET | JWT | List devices with status |
| `GET /api/v1/devices/{id}` | GET | JWT | Device detail + last reading |
| `POST /api/v1/devices` | POST | ADMIN | Register device → API key |
| `PUT /api/v1/devices/{id}` | PUT | ADMIN | Update metadata |
| `POST /api/v1/devices/{id}/command` | POST | ADMIN | Publish MQTT command |

### 4.3 Telemetry

| Endpoint | Method | Auth | Description |
| :--- | :---: | :---: | :--- |
| `GET /api/v1/telemetry/latest` | GET | JWT | Latest reading (`?device_id=`) |
| `GET /api/v1/telemetry/history` | GET | JWT | Raw range query (`?device_id=&start=&end=`) |
| `GET /api/v1/telemetry/aggregate` | GET | JWT | `date_trunc()` aggregate (`?interval=5min`) |
| `GET /api/v1/telemetry/hourly` | GET | JWT | Hourly materialized view |
| `GET /api/v1/telemetry/daily` | GET | JWT | Daily materialized view |

### 4.4 Forecast & Alerts

| Endpoint | Method | Auth | Description |
| :--- | :---: | :---: | :--- |
| `GET /api/v1/forecast/{device_id}` | GET | JWT | Predicted AQI 1h/6h/24h |
| `POST /api/v1/forecast/run` | POST | ADMIN | Trigger manual forecast |
| `GET /api/v1/alerts` | GET | JWT | Active alerts |
| `PUT /api/v1/alerts/{id}/resolve` | PUT | JWT | Mark resolved |
| `GET /actuator/health` | GET | — | Liveness check |

---

## 5. WebSocket (STOMP) Real-Time Streaming

### 5.1 Endpoints

| Endpoint | Transport | Description |
| :--- | :--- | :--- |
| `/ws` | Native WebSocket | STOMP connection |
| `/ws` + SockJS | HTTP fallback | Browser compatibility |

### 5.2 STOMP Topics

| Destination | Direction | Description |
| :--- | :---: | :--- |
| `/topic/telemetry` | Server → Client | All devices broadcast |
| `/topic/devices/{id}` | Server → Client | Single device stream |
| `/topic/alerts` | Server → Client | Alert notifications |
| `/topic/device-status` | Server → Client | Online/offline changes |

### 5.3 Auth

- JWT validated on STOMP `CONNECT` frame via `ChannelInterceptor`
- Token in STOMP header `Authorization: Bearer <jwt>`

---

## 6. Authentication & Security

### 6.1 Dual Auth

```
Request ──► DeviceApiKeyAuthFilter ──► JwtAuthenticationFilter ──► SecurityFilterChain ──► Controller

ESP32 Device:  X-API-Key header  → SHA-256 hash → lookup devices.api_key_hash → ROLE_DEVICE
Dashboard:     Authorization: Bearer <JWT> → JJWT decode → validate exp, sig → ROLE_USER/ADMIN
```

### 6.2 Details

- **Passwords**: BCrypt via Spring `PasswordEncoder`
- **Device keys**: SHA-256 hash in `devices.api_key_hash`
- **JWT**: JJWT 0.12, HS256, 30-min access / 7-day refresh
- **RBAC**: `ROLE_ADMIN`, `ROLE_USER`, `ROLE_VIEWER`, `ROLE_DEVICE`
- **Session**: `SessionCreationPolicy.STATELESS`

---

## 7. JPA Entity Design

### 7.1 TelemetryData Entity

```java
@Entity
@Table(name = "telemetry_data")
public class TelemetryData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "device_id", length = 32, nullable = false)
    private String deviceId;

    @Column(nullable = false) private Float pm25;
    @Column(nullable = false) private Float co2;
    @Column(nullable = false) private Float temperature;
    @Column(nullable = false) private Float humidity;
    @Column(name = "aqi_index", nullable = false) private Short aqiIndex;
    @Column(name = "aqi_level", length = 30, nullable = false) private String aqiLevel;
    
    @Column(name = "wifi_rssi") private Short wifiRssi;
    @Column(name = "uptime_sec") private Integer uptimeSec;
    @Column(name = "free_heap") private Integer freeHeap;
}
```

### 7.2 Repository with Native Queries

```java
@Repository
public interface TelemetryRepository extends JpaRepository<TelemetryData, Long> {

    // Latest reading (fast via idx_telemetry_device_time_desc)
    @Query(value = "SELECT * FROM telemetry_data WHERE device_id = :deviceId " +
                   "ORDER BY recorded_at DESC LIMIT 1", nativeQuery = true)
    Optional<TelemetryData> findLatestByDeviceId(@Param("deviceId") String deviceId);

    // Time range query
    List<TelemetryData> findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(
        String deviceId, Instant start, Instant end);

    // Aggregation with date_trunc
    @Query(value = """
        SELECT date_trunc(:interval, recorded_at) AS bucket,
               device_id AS deviceId,
               AVG(pm25)::real AS avgPm25, MAX(pm25)::real AS maxPm25,
               AVG(co2)::real AS avgCo2,
               AVG(temperature)::real AS avgTemp,
               AVG(humidity)::real AS avgHum,
               AVG(aqi_index)::smallint AS avgAqi,
               COUNT(*) AS sampleCount
        FROM telemetry_data
        WHERE device_id = :deviceId
          AND recorded_at >= :start AND recorded_at <= :end
        GROUP BY bucket, device_id
        ORDER BY bucket ASC
        """, nativeQuery = true)
    List<TelemetryAggregateProjection> findAggregates(
        @Param("deviceId") String deviceId,
        @Param("interval") String interval,
        @Param("start") Instant start,
        @Param("end") Instant end);
}
```

---

## 8. Key Query Patterns

```sql
-- Latest reading for one device (sub-ms via index)
SELECT * FROM telemetry_data
WHERE device_id = 'ESP32S3_AQSS_01'
ORDER BY recorded_at DESC LIMIT 1;

-- Latest reading per ALL devices
SELECT DISTINCT ON (device_id) *
FROM telemetry_data
WHERE recorded_at >= NOW() - INTERVAL '1 hour'
ORDER BY device_id, recorded_at DESC;

-- Raw 24h history for chart
SELECT recorded_at, pm25, co2, temperature, humidity, aqi_index
FROM telemetry_data
WHERE device_id = 'ESP32S3_AQSS_01'
  AND recorded_at >= NOW() - INTERVAL '24 hours'
ORDER BY recorded_at ASC;

-- Downsampled 5-min intervals for 7-day chart
SELECT date_trunc('hour', recorded_at) AS bucket,
       AVG(pm25) AS avg_pm25, AVG(co2) AS avg_co2,
       AVG(temperature) AS avg_temp, AVG(humidity) AS avg_hum
FROM telemetry_data
WHERE device_id = 'ESP32S3_AQSS_01'
  AND recorded_at >= NOW() - INTERVAL '7 days'
GROUP BY bucket ORDER BY bucket ASC;

-- Cursor-based pagination (no OFFSET)
SELECT * FROM telemetry_data
WHERE device_id = 'ESP32S3_AQSS_01'
  AND recorded_at < :cursor_timestamp
ORDER BY recorded_at DESC LIMIT 50;
```

---

## 9. Project Structure

```
backend/
├── src/main/java/com/aqss/backend/
│   ├── BackendApplication.java
│   ├── config/
│   │   ├── MqttIntegrationConfig.java
│   │   ├── WebSocketBrokerConfig.java
│   │   ├── SecurityConfig.java
│   │   └── JacksonConfig.java
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── DeviceController.java
│   │   ├── TelemetryController.java
│   │   ├── ForecastController.java
│   │   └── AlertController.java
│   ├── dto/
│   │   ├── auth/
│   │   ├── device/
│   │   └── telemetry/
│   ├── entity/
│   │   ├── Device.java
│   │   ├── User.java
│   │   ├── TelemetryData.java
│   │   ├── Alert.java
│   │   └── Prediction.java
│   ├── mapper/
│   │   ├── DeviceMapper.java
│   │   ├── TelemetryMapper.java
│   │   └── UserMapper.java
│   ├── mqtt/
│   │   ├── MqttTelemetryHandler.java
│   │   └── MqttCommandPublisher.java
│   ├── repository/
│   │   ├── DeviceRepository.java
│   │   ├── UserRepository.java
│   │   ├── TelemetryRepository.java
│   │   ├── AlertRepository.java
│   │   └── projection/
│   │       └── TelemetryAggregateProjection.java
│   ├── security/
│   │   ├── filter/
│   │   │   ├── JwtAuthenticationFilter.java
│   │   │   └── DeviceApiKeyAuthFilter.java
│   │   ├── jwt/
│   │   │   └── JwtService.java
│   │   └── websocket/
│   │       └── StompAuthChannelInterceptor.java
│   └── service/
│       ├── AuthService.java
│       ├── DeviceService.java
│       ├── DeviceStatusService.java
│       ├── TelemetryIngestionService.java
│       ├── AqiCalculatorService.java
│       ├── AlertService.java
│       └── PartitionManagerService.java  # Auto-create partitions + refresh matviews
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__init_schema.sql
│       ├── V2__partition_management.sql
│       └── V3__materialized_views.sql
├── src/test/java/com/aqss/backend/
├── mosquitto/config/mosquitto.conf
├── pom.xml
├── Dockerfile
└── docker-compose.yml
```

---

## 10. Configuration (`application.yml`)

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
      connection-timeout: 20000
      max-lifetime: 1200000
      leak-detection-threshold: 5000
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc.batch_size: 100
        order_inserts: true
  flyway:
    enabled: true
    baseline-on-migrate: true

mqtt:
  broker-url: tcp://${MQTT_HOST:localhost}:1883
  client-id: aqss-backend-server
  username: ${MQTT_USER:}
  password: ${MQTT_PASS:}
  topics:
    telemetry: "aqss/devices/+/telemetry"
    status: "aqss/devices/+/status"

security:
  jwt:
    secret: ${JWT_SECRET}
    access-token-expiration-ms: 1800000
    refresh-token-expiration-ms: 604800000

aqss:
  telemetry:
    batch-flush-interval-ms: 1000
```

---

## 11. Docker Compose

```yaml
version: '3.8'
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
    ports: ["1883:1883", "9001:9001"]
    volumes: ["./mosquitto/config/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro"]

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    ports: ["6379:6379"]

  backend:
    build: .
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      DB_HOST: postgres
      DB_NAME: aqss_db
      DB_USER: aqss_user
      DB_PASS: ${POSTGRES_PASSWORD}
      MQTT_HOST: mosquitto
      JWT_SECRET: ${JWT_SECRET}
    ports: ["8080:8080"]

volumes:
  pgdata:
```

---

## 12. Maven Dependencies (`pom.xml`)

```xml
<properties>
    <java.version>21</java.version>
    <spring-boot.version>3.3.3</spring-boot.version>
    <jjwt.version>0.12.6</jjwt.version>
    <mapstruct.version>1.5.5.Final</mapstruct.version>
</properties>

<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- MQTT -->
    <dependency>
        <groupId>org.springframework.integration</groupId>
        <artifactId>spring-integration-mqtt</artifactId>
    </dependency>

    <!-- Database -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>${jjwt.version}</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>${jjwt.version}</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>${jjwt.version}</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>commons-codec</groupId>
        <artifactId>commons-codec</artifactId>
        <version>1.17.1</version>
    </dependency>

    <!-- MapStruct & Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.mapstruct</groupId>
                        <artifactId>mapstruct-processor</artifactId>
                        <version>${mapstruct.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok-mapstruct-binding</artifactId>
                        <version>0.2.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## 13. Performance Targets

| Metric | Target |
| :--- | :--- |
| MQTT → DB latency | < 1s (batch flush) |
| MQTT → STOMP WebSocket | < 5 ms |
| REST API p95 | < 100 ms |
| Concurrent MQTT devices | 100+ |
| Concurrent WebSocket clients | 500+ |
| DB write throughput | 20 rows/sec (100 devices × 0.2 Hz) |
| Storage (100 devices, 90 days) | ~5–8 GB uncompressed |
| Materialized view refresh | 15-min cycle (hourly view) |

---

## 14. Verification Plan

### Automated Tests
```bash
./mvnw test       # Unit + Integration (Testcontainers PostgreSQL 16)
```

### Manual Verification
1. `docker-compose up -d` — all services healthy
2. Flyway runs V1/V2/V3 — tables + partitions + matviews created
3. Publish test MQTT → verify DB insert + STOMP broadcast
4. Swagger UI `http://localhost:8080/swagger-ui.html` — test endpoints
5. Connect STOMP client → subscribe `/topic/devices/ESP32S3_AQSS_01`
6. Check partitions: `SELECT * FROM pg_catalog.pg_inherits;`
7. Check matview freshness: `SELECT * FROM telemetry_hourly LIMIT 10;`
