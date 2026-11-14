-- AQSS V6 — AI worker, model registry, forecast ledger and alerts.
--
-- The backend owns metadata and timing; artifacts and snapshots live on the worker's disk. Rows
-- here survive device unpair/delete because they are evidence, so there are no cascading FKs from
-- the operational tables. Forecast points cascade from their run only.

CREATE TABLE IF NOT EXISTS ai_workers (
    id                VARCHAR(64) PRIMARY KEY,
    last_heartbeat_at TIMESTAMPTZ,
    capabilities      JSONB,
    model_artifact    VARCHAR(200),
    status            VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
);

CREATE TABLE IF NOT EXISTS ai_jobs (
    id              UUID PRIMARY KEY,
    type            VARCHAR(20) NOT NULL,          -- train | evaluate | export
    device_id       VARCHAR(32),
    params          JSONB,
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    worker_id       VARCHAR(64),
    lease_token     VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    progress        INTEGER NOT NULL DEFAULT 0,
    phase           VARCHAR(30),
    data_cutoff     TIMESTAMPTZ,
    result          JSONB,
    error           TEXT,
    attempts        INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_jobs_status_created ON ai_jobs (status, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_jobs_device          ON ai_jobs (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_dataset_snapshots (
    id               UUID PRIMARY KEY,
    device_id        VARCHAR(32) NOT NULL,
    from_at          TIMESTAMPTZ NOT NULL,
    to_at            TIMESTAMPTZ NOT NULL,
    manifest         JSONB NOT NULL,
    context_manifest JSONB,
    counts           JSONB,
    snapshot_hash    VARCHAR(64),
    status           VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_snapshot_device_created ON ai_dataset_snapshots (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_models (
    id                UUID PRIMARY KEY,
    device_id         VARCHAR(32),
    version           VARCHAR(80) NOT NULL,
    algorithm         VARCHAR(40),
    feature_version   VARCHAR(20),
    dataset_snapshot_id UUID,
    dataset_hash      VARCHAR(64),
    params            JSONB,
    train_cutoff      TIMESTAMPTZ,
    metrics           JSONB,
    artifact_hash     VARCHAR(64),
    artifact_path     VARCHAR(255),
    status            VARCHAR(20) NOT NULL DEFAULT 'EXPERIMENTAL',
    promotion_reason  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (device_id, version)
);
CREATE INDEX IF NOT EXISTS idx_model_device_created ON ai_models (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS forecast_runs (
    id              UUID PRIMARY KEY,
    device_id       VARCHAR(32) NOT NULL,
    model_id        UUID,
    model_version   VARCHAR(80) NOT NULL,
    input_cutoff    TIMESTAMPTZ NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    profile_version INTEGER,
    threshold_pm25  REAL,
    data_quality    JSONB,
    worker_id       VARCHAR(64),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',
    UNIQUE (device_id, model_version, input_cutoff)
);
CREATE INDEX IF NOT EXISTS idx_forecast_device_issued ON forecast_runs (device_id, issued_at DESC);

CREATE TABLE IF NOT EXISTS forecast_points (
    id                BIGSERIAL PRIMARY KEY,
    run_id            UUID NOT NULL REFERENCES forecast_runs (id) ON DELETE CASCADE,
    horizon_minutes   INTEGER NOT NULL,
    target_at         TIMESTAMPTZ NOT NULL,
    pm25              REAL NOT NULL,
    actual_pm25       REAL,
    error             REAL,
    evaluation_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    evaluated_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_point_target_status ON forecast_points (target_at, evaluation_status);
CREATE INDEX IF NOT EXISTS idx_point_run            ON forecast_points (run_id);

CREATE TABLE IF NOT EXISTS ai_alerts (
    id                  UUID PRIMARY KEY,
    device_id           VARCHAR(32) NOT NULL,
    forecast_run_id     UUID,
    type                VARCHAR(30) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    threshold_snapshot  REAL,
    horizon_minutes     INTEGER,
    reason              TEXT,
    lifecycle           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    feedback            VARCHAR(30),
    feedback_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_alert_device_created ON ai_alerts (device_id, created_at DESC);
