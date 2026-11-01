-- AQSS Layer 2 — minimal schema: spaces, devices, telemetry, predictions, audit.
-- Thresholds live on the space; there is no auth, no partitioned telemetry, no materialized view.

CREATE TABLE spaces (
    code                 VARCHAR(20) PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    volume_m3            REAL,
    purpose              VARCHAR(30),
    region               VARCHAR(50),
    ventilation_estimate VARCHAR(20),
    threshold_pm25       REAL         NOT NULL,
    threshold_source     VARCHAR(10)  NOT NULL DEFAULT 'manual',  -- manual | ai
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE devices (
    device_id             VARCHAR(32) PRIMARY KEY,
    name                  VARCHAR(100),
    space_code            VARCHAR(20) REFERENCES spaces (code) ON DELETE SET NULL,
    fan_state             VARCHAR(3)  NOT NULL DEFAULT 'OFF',
    fan_level             SMALLINT    NOT NULL DEFAULT 0,
    fan_mode              VARCHAR(6)  NOT NULL DEFAULT 'AUTO',
    on_since              TIMESTAMPTZ,
    last_command_at       TIMESTAMPTZ,
    telemetry_interval_ms INTEGER     NOT NULL DEFAULT 5000,
    dust_baseline_us      REAL        NOT NULL DEFAULT 1400,
    last_seen_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE telemetry (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    pm25        REAL,
    co2         REAL,
    temperature REAL,
    humidity    REAL,
    wifi_rssi   SMALLINT
);

CREATE INDEX idx_telemetry_device_time ON telemetry (device_id, recorded_at DESC);

CREATE TABLE predictions (
    id            BIGSERIAL PRIMARY KEY,
    device_id     VARCHAR(32) NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
    predicted_at  TIMESTAMPTZ NOT NULL,
    model_version VARCHAR(20),
    points        TEXT        NOT NULL   -- JSON [{target_time, pm25_q10, pm25_q50, pm25_q90}]
);

CREATE INDEX idx_predictions_device_time ON predictions (device_id, predicted_at DESC);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor       VARCHAR(10) NOT NULL,   -- USER | BACKEND | DEVICE | AI
    action      VARCHAR(20) NOT NULL,
    device_id   VARCHAR(32),
    detail      TEXT
);

CREATE INDEX idx_audit_time ON audit_log (occurred_at DESC);

-- Demo data so the dashboard has something to show on first boot.
INSERT INTO spaces (code, name, volume_m3, purpose, region, ventilation_estimate, threshold_pm25, threshold_source)
VALUES ('SP_201', 'Phòng 201', 15.0, 'bedroom', 'hanoi_caugiay', 'low', 35.0, 'manual');

INSERT INTO devices (device_id, name, space_code, telemetry_interval_ms, dust_baseline_us)
VALUES ('AQSS_ESP32S3_01', 'Node phòng 201', 'SP_201', 5000, 1400.0);
