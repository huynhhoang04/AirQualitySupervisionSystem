-- AQSS V4.2 — remove the external AI layer.
--
-- Layer 3 (threshold / dosage / forecast) is gone: the threshold is now computed in-process by
-- ThresholdService from the room profile and live CO₂, and the fan level on a breach is fixed.
-- This drops the table the forecast used to be cached in.

DROP TABLE IF EXISTS predictions;
