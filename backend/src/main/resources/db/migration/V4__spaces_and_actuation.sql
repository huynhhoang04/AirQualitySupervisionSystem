-- AQSS V2 — V4__spaces_and_actuation.sql
-- Space becomes a first-class entity; the control loop, thresholds and actuator
-- registry are all scoped to a Space.
--
-- Contract decision (approved): `spaces.code` (e.g. 'SP_201') is the device-facing
-- identifier carried by telemetry (`telemetry_data.space_id`) and MQTT `set_space`.
-- `spaces.id` (BIGSERIAL) remains the internal FK for space_thresholds, actuators,
-- control_decisions and predictions.

-- ─── Space registry ────────────────────────────────────────────────
CREATE TABLE spaces (
    id                   BIGSERIAL PRIMARY KEY,
    code                 VARCHAR(20) UNIQUE NOT NULL,   -- device-facing id, e.g. 'SP_201'
    name                 VARCHAR(100) NOT NULL,
    area_m2              REAL,
    ceiling_m            REAL,
    volume_m3            REAL NOT NULL,
    purpose              VARCHAR(30) NOT NULL,          -- bedroom|dorm|classroom|office
    region               VARCHAR(50) NOT NULL,          -- e.g. hanoi_caugiay
    ventilation_estimate VARCHAR(20) DEFAULT 'low',     -- low|medium|high
    created_at           TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Node ↔ space mapping ──────────────────────────────────────────
CREATE TABLE space_devices (
    space_id   BIGINT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    device_id  VARCHAR(32) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL DEFAULT 'SENSOR',   -- SENSOR|ACTUATOR|COMBINED
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
    current_state    VARCHAR(10) DEFAULT 'OFF',    -- ON|OFF
    current_level    SMALLINT DEFAULT 0,           -- 0-3
    mode             VARCHAR(10) DEFAULT 'AUTO',   -- AUTO|MANUAL
    duty_pct         SMALLINT DEFAULT 0,
    filter_runtime_h REAL DEFAULT 0,
    last_command_at  TIMESTAMPTZ,
    state_since      TIMESTAMPTZ DEFAULT NOW()
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

-- ─── Seed data so the loop is testable immediately ─────────────────
-- Demo device (api key = "dev-device-api-key-01") + Space 201 (the concept doc example).
INSERT INTO devices (device_id, name, location_name, api_key_hash, firmware_version, is_active)
VALUES ('AQSS_ESP32S3_01', 'Node phòng 201', 'Cầu Giấy, Hà Nội',
        encode(digest('dev-device-api-key-01', 'sha256'), 'hex'), '2.0.0', TRUE)
ON CONFLICT (device_id) DO NOTHING;

INSERT INTO spaces (code, name, area_m2, ceiling_m, volume_m3, purpose, region, ventilation_estimate)
VALUES ('SP_201', 'Phòng 201', 5.0, 3.0, 15.0, 'bedroom', 'hanoi_caugiay', 'low')
ON CONFLICT (code) DO NOTHING;

INSERT INTO space_devices (space_id, device_id, role)
SELECT s.id, 'AQSS_ESP32S3_01', 'COMBINED' FROM spaces s WHERE s.code = 'SP_201'
ON CONFLICT DO NOTHING;

INSERT INTO actuators (device_id, space_id, current_state, current_level, mode, duty_pct)
SELECT 'AQSS_ESP32S3_01', s.id, 'OFF', 0, 'AUTO', 0 FROM spaces s WHERE s.code = 'SP_201'
ON CONFLICT (device_id) DO NOTHING;
