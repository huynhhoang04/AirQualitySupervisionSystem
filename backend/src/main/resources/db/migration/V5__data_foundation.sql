-- AQSS V6 — data foundation for learning and evidence.
--
-- Nothing here changes the MQTT wire format. These columns and tables exist so that telemetry,
-- actuator reports and commands keep their provenance: what the device said, when the backend
-- received it, and what the backend asked for. Legacy rows keep NULL provenance rather than a
-- fabricated timestamp.

-- ── telemetry provenance ─────────────────────────────────────────────────────
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS received_at    TIMESTAMPTZ;
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS time_quality   VARCHAR(20);   -- device | server_assigned | legacy
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS quality_flags  JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS dust_fault     BOOLEAN;
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS uptime_sec     BIGINT;
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS free_heap      BIGINT;
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS min_free_heap  BIGINT;
ALTER TABLE telemetry ADD COLUMN IF NOT EXISTS payload_hash   VARCHAR(64);

UPDATE telemetry SET time_quality = 'legacy' WHERE time_quality IS NULL;

CREATE INDEX IF NOT EXISTS idx_telemetry_device_recorded_id ON telemetry (device_id, recorded_at, id);
CREATE INDEX IF NOT EXISTS idx_telemetry_received           ON telemetry (received_at, id);

-- Idempotent ingestion: an identical canonical payload for a device is stored once. A different
-- payload carrying the same device timestamp is kept (it is a conflict, not a duplicate).
CREATE UNIQUE INDEX IF NOT EXISTS uq_telemetry_payload
    ON telemetry (device_id, payload_hash) WHERE payload_hash IS NOT NULL;

-- ── device liveness and reported state ───────────────────────────────────────
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_telemetry_received_at TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_actuator_received_at  TIMESTAMPTZ;

-- ── space profile ────────────────────────────────────────────────────────────
ALTER TABLE spaces ADD COLUMN IF NOT EXISTS occupancy_hours  INTEGER;
ALTER TABLE spaces ADD COLUMN IF NOT EXISTS profile_version  INTEGER NOT NULL DEFAULT 1;

-- ── actuator history: what the device reported, append-only ──────────────────
CREATE TABLE IF NOT EXISTS actuator_history (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(32) NOT NULL,
    recorded_at     TIMESTAMPTZ,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    state           VARCHAR(3),
    level           SMALLINT,
    duty_pct        SMALLINT,
    mode            VARCHAR(6),
    filter_runtime_s INTEGER,
    quality         JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_actuator_device_received ON actuator_history (device_id, received_at DESC);

-- ── command log: intent vs. observation ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS command_log (
    id               UUID PRIMARY KEY,
    device_id        VARCHAR(32) NOT NULL,
    command          VARCHAR(40) NOT NULL,
    params           JSONB,
    actor            VARCHAR(10) NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at     TIMESTAMPTZ,
    matched_at       TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    error            TEXT,
    expires_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_command_device_requested ON command_log (device_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_command_status_requested ON command_log (status, requested_at);

-- ── space profile versions: enough context to replay an old forecast ─────────
CREATE TABLE IF NOT EXISTS space_profile_versions (
    id               BIGSERIAL PRIMARY KEY,
    space_code       VARCHAR(20) NOT NULL,
    version          INTEGER     NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    profile          JSONB,
    threshold_pm25   REAL,
    threshold_source VARCHAR(10),
    factors          JSONB,
    UNIQUE (space_code, version)
);
CREATE INDEX IF NOT EXISTS idx_profile_space_effective ON space_profile_versions (space_code, effective_at DESC);

-- ── experiments + room events (operator context, never required for a model) ─
CREATE TABLE IF NOT EXISTS experiments (
    id               UUID PRIMARY KEY,
    device_id        VARCHAR(32),
    space_code       VARCHAR(20),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    stopped_at       TIMESTAMPTZ,
    protocol_version VARCHAR(20),
    group_label      VARCHAR(30),
    conditions       JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'open',
    notes            TEXT
);
CREATE INDEX IF NOT EXISTS idx_experiment_device_started ON experiments (device_id, started_at DESC);

CREATE TABLE IF NOT EXISTS room_events (
    id            UUID PRIMARY KEY,
    device_id     VARCHAR(32),
    space_code    VARCHAR(20),
    occurred_at   TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    type          VARCHAR(30) NOT NULL,
    note          TEXT,
    experiment_id UUID REFERENCES experiments (id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_room_event_device_occurred ON room_events (device_id, occurred_at DESC);
