-- AQSS V2 — V3__materialized_views.sql
-- Hourly / daily rollups. AQI is NOT stored (derived at read time), so there are
-- no avg_aqi / max_aqi columns. Unique indexes on (device_id, bucket) allow
-- REFRESH MATERIALIZED VIEW CONCURRENTLY.

CREATE MATERIALIZED VIEW telemetry_hourly AS
SELECT
    device_id,
    space_id,
    date_trunc('hour', recorded_at) AS bucket,
    avg(pm25)        AS avg_pm25,        min(pm25)        AS min_pm25,        max(pm25)        AS max_pm25,
    avg(co2)         AS avg_co2,         min(co2)         AS min_co2,         max(co2)         AS max_co2,
    avg(temperature) AS avg_temperature, min(temperature) AS min_temperature, max(temperature) AS max_temperature,
    avg(humidity)    AS avg_humidity,    min(humidity)    AS min_humidity,    max(humidity)    AS max_humidity,
    count(*)         AS sample_count
FROM telemetry_data
GROUP BY device_id, space_id, bucket;

CREATE UNIQUE INDEX uq_telemetry_hourly_device_bucket ON telemetry_hourly (device_id, bucket);
CREATE INDEX idx_telemetry_hourly_space_bucket ON telemetry_hourly (space_id, bucket DESC);

CREATE MATERIALIZED VIEW telemetry_daily AS
SELECT
    device_id,
    space_id,
    date_trunc('day', recorded_at) AS bucket,
    avg(pm25)        AS avg_pm25,        min(pm25)        AS min_pm25,        max(pm25)        AS max_pm25,
    avg(co2)         AS avg_co2,         min(co2)         AS min_co2,         max(co2)         AS max_co2,
    avg(temperature) AS avg_temperature, min(temperature) AS min_temperature, max(temperature) AS max_temperature,
    avg(humidity)    AS avg_humidity,    min(humidity)    AS min_humidity,    max(humidity)    AS max_humidity,
    count(*)         AS sample_count
FROM telemetry_data
GROUP BY device_id, space_id, bucket;

CREATE UNIQUE INDEX uq_telemetry_daily_device_bucket ON telemetry_daily (device_id, bucket);
CREATE INDEX idx_telemetry_daily_space_bucket ON telemetry_daily (space_id, bucket DESC);
