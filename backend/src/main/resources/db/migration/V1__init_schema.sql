-- AQSS V2 — V1__init_schema.sql
-- Core schema: devices, users, partitioned telemetry, alerts, quantile predictions.
-- AQI is NOT persisted (derived at read time via NowCast); no aqi_* columns anywhere.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- sha256()/digest() for device API-key hashes

-- ─── Device registry ───────────────────────────────────────────────
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

-- ─── Users (RBAC) ──────────────────────────────────────────────────
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

-- ─── Telemetry (monthly range partitions) ─────────────────────────
-- Composite PK (id, recorded_at) is required: the partition key must be part
-- of every unique constraint. The JPA entity maps it with @EmbeddedId and does
-- NOT use @GeneratedValue (writes go through JdbcTemplate batch, id = default).
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
CREATE INDEX idx_telemetry_space_time_desc  ON telemetry_data (space_id, recorded_at DESC);

-- Initial partitions: current month −1 .. +3 so ingestion never fails on a fresh DB.
DO $$
DECLARE
    m     DATE;
    pname TEXT;
BEGIN
    FOR i IN -1..3 LOOP
        m := (date_trunc('month', NOW()) + (i || ' month')::INTERVAL)::DATE;
        pname := 'telemetry_data_' || to_char(m, 'YYYY_MM');
        IF NOT EXISTS (
            SELECT 1 FROM pg_class
            WHERE relname = pname AND relnamespace = 'public'::regnamespace
        ) THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF telemetry_data FOR VALUES FROM (%L) TO (%L)',
                pname, m, m + INTERVAL '1 month');
        END IF;
    END LOOP;
END $$;

-- ─── Alerts ────────────────────────────────────────────────────────
CREATE TABLE alerts (
    id                 BIGSERIAL PRIMARY KEY,
    space_id           BIGINT,
    device_id          VARCHAR(32) REFERENCES devices(device_id),
    alert_type         VARCHAR(30) NOT NULL,   -- PM25_THRESHOLD | CO2_ALERT | SENSOR_OFFLINE | SENSOR_SATURATED
    severity           VARCHAR(10) NOT NULL,   -- INFO | WARNING | CRITICAL
    message            TEXT,
    triggered_at       TIMESTAMPTZ DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ
);
CREATE INDEX idx_alerts_active ON alerts (device_id) WHERE resolved_at IS NULL;

-- ─── Quantile forecasts (DL, Phase 8) ──────────────────────────────
CREATE TABLE predictions (
    id                 BIGSERIAL PRIMARY KEY,
    space_id           BIGINT,
    predicted_at       TIMESTAMPTZ DEFAULT NOW(),
    forecast_data      JSONB NOT NULL,   -- [{target_time, pm25_q50, pm25_q10, pm25_q90}] × 96
    model_version      VARCHAR(20) NOT NULL
);
CREATE INDEX idx_predictions_space_time ON predictions (space_id, predicted_at DESC);
