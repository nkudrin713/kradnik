DROP VIEW IF EXISTS analytics_job_latency_daily;
DROP VIEW IF EXISTS analytics_job_lifecycle;
DROP VIEW IF EXISTS analytics_user_retention_daily;
DROP VIEW IF EXISTS analytics_duration_buckets;
DROP VIEW IF EXISTS analytics_failures_daily;
DROP VIEW IF EXISTS analytics_cache_daily;
DROP VIEW IF EXISTS analytics_downloads_daily;
DROP VIEW IF EXISTS analytics_user_activity_daily;

DROP TABLE IF EXISTS analytics_events;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'kradnik_analytics_reader'
    ) THEN
        EXECUTE format(
            'REVOKE CONNECT ON DATABASE %I FROM kradnik_analytics_reader',
            current_database()
        );
        EXECUTE 'REVOKE USAGE ON SCHEMA public FROM kradnik_analytics_reader';
        EXECUTE 'DROP ROLE kradnik_analytics_reader';
    END IF;
END
$$;
