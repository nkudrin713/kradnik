DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'kradnik_analytics_reader'
    ) THEN
        CREATE ROLE kradnik_analytics_reader LOGIN;
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format(
        'GRANT CONNECT ON DATABASE %I TO kradnik_analytics_reader',
        current_database()
    );
END
$$;

GRANT USAGE ON SCHEMA public TO kradnik_analytics_reader;

GRANT SELECT ON
    analytics_events,
    analytics_user_activity_daily,
    analytics_downloads_daily,
    analytics_cache_daily,
    analytics_failures_daily,
    analytics_duration_buckets
TO kradnik_analytics_reader;
