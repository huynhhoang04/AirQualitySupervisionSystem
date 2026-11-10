-- AQSS V4.1 — what the room screen needs and the backend was discarding.
--
-- filter_runtime_s: the node counts fan hours and publishes `filter_runtime_h` on every actuator
-- state; the backend received it and threw it away. It is the second thing a filter owner cares
-- about, after air quality.
--
-- last_reason / last_decision_at: the control loop already writes why it acted into audit_log, but
-- the room screen needs that answer on the device itself, not at the end of a 50-row JSON list.

ALTER TABLE devices ADD COLUMN IF NOT EXISTS filter_runtime_s  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS filter_replaced_at TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_reason      VARCHAR(30);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_decision_at TIMESTAMPTZ;

-- Existing rows: the next actuator-state message fills the runtime in. Mark them as replaced now so
-- a freshly migrated node does not read as "overdue" before it has reported once.
UPDATE devices SET filter_replaced_at = created_at WHERE filter_replaced_at IS NULL;
