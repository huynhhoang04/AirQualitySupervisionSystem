-- AQSS V4 — device pairing + the two fields the control loop needs.
-- Pairing: a node registers on first message but stays hidden until an operator links it to a
-- room by entering the 6-character code shown on its OLED.

ALTER TABLE devices ADD COLUMN pairing_code VARCHAR(8);
ALTER TABLE devices ADD COLUMN paired_at    TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN off_since    TIMESTAMPTZ;

CREATE INDEX idx_devices_pairing_code ON devices (pairing_code);

-- A GP2Y1023 at its rail reports ~393 ug/m3 as if it were a measurement; the flag travels with it.
ALTER TABLE telemetry ADD COLUMN pm25_saturated BOOLEAN NOT NULL DEFAULT FALSE;

-- Rows that already carry a room were paired before this column existed.
UPDATE devices SET paired_at = created_at WHERE space_code IS NOT NULL AND paired_at IS NULL;
