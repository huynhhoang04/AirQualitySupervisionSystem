-- V7 — the 360-step forecast curve, stored as one array per run.
--
-- A run every 5 seconds is 17,280 runs/day/device and 6,220,800 values/day/device. One row per
-- value is not storable; one REAL[360] per run is about 24 MiB/day before metadata and TOAST.
--
-- The three scored milestones stay in forecast_points: they are the evidence the accuracy panel
-- uses, and they are kept longer than the full curve.

CREATE TABLE IF NOT EXISTS forecast_paths (
    run_id       UUID PRIMARY KEY REFERENCES forecast_runs (id) ON DELETE CASCADE,
    anchor_at    TIMESTAMPTZ NOT NULL,
    anchor_pm25  REAL NOT NULL,
    step_seconds INTEGER NOT NULL DEFAULT 5,
    values       REAL[] NOT NULL,
    q10          REAL[],
    q90          REAL[],
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Retention scans by age; the run's issued_at is already indexed.
CREATE INDEX IF NOT EXISTS idx_path_anchor ON forecast_paths (anchor_at DESC);

COMMENT ON TABLE forecast_paths IS
    'Median forecast curve per run. values[k-1] is the value at anchor_at + k*step_seconds.';
