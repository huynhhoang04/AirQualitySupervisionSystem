-- AQSS V2 — V2__partition_management.sql
-- Partition lifecycle helpers. The V1 audit found that create_monthly_partition()
-- existed but was never invoked, so inserts would fail once the pre-created
-- partitions ran out. PartitionManagerService invokes these on a schedule.

-- Create (if absent) a partition covering [from_month, from_month + 1 month).
CREATE OR REPLACE FUNCTION create_partition_for(p_month DATE)
RETURNS TEXT AS $$
DECLARE
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    start_date := date_trunc('month', p_month)::DATE;
    end_date   := (start_date + INTERVAL '1 month')::DATE;
    partition_name := 'telemetry_data_' || to_char(start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = partition_name
          AND relnamespace = 'public'::regnamespace
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF telemetry_data FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date);
        RETURN partition_name;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Backwards-compatible wrapper: ensure next month exists.
CREATE OR REPLACE FUNCTION create_monthly_partition()
RETURNS void AS $$
BEGIN
    PERFORM create_partition_for((date_trunc('month', NOW()) + INTERVAL '1 month')::DATE);
END;
$$ LANGUAGE plpgsql;
