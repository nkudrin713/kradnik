CREATE VIEW analytics_user_activity_daily AS
WITH first_seen AS (
    SELECT
        environment,
        telegram_user_id,
        MIN(created_at::date) AS first_seen_date
    FROM analytics_events
    WHERE telegram_user_id IS NOT NULL
      AND event_type = 'download_requested'
    GROUP BY environment, telegram_user_id
),
daily_users AS (
    SELECT DISTINCT
        environment,
        created_at::date AS activity_date,
        telegram_user_id
    FROM analytics_events
    WHERE telegram_user_id IS NOT NULL
      AND event_type = 'download_requested'
)
SELECT
    daily_users.environment,
    daily_users.activity_date,
    COUNT(*) AS active_users,
    COUNT(*) FILTER (WHERE first_seen.first_seen_date = daily_users.activity_date) AS new_users,
    COUNT(*) FILTER (WHERE first_seen.first_seen_date < daily_users.activity_date) AS returning_users
FROM daily_users
JOIN first_seen
  ON first_seen.environment = daily_users.environment
 AND first_seen.telegram_user_id = daily_users.telegram_user_id
GROUP BY daily_users.environment, daily_users.activity_date;

CREATE VIEW analytics_downloads_daily AS
SELECT
    environment,
    created_at::date AS activity_date,
    COALESCE(platform, 'unknown') AS platform,
    COALESCE(output_type, 'unknown') AS output_type,
    COUNT(*) FILTER (WHERE event_type = 'download_requested') AS requested_count,
    COUNT(*) FILTER (WHERE event_type = 'download_completed') AS completed_count,
    COUNT(*) FILTER (WHERE event_type = 'download_failed') AS failed_count,
    COUNT(*) FILTER (WHERE event_type = 'download_rejected') AS rejected_count
FROM analytics_events
WHERE event_type IN (
    'download_requested',
    'download_completed',
    'download_failed',
    'download_rejected'
)
GROUP BY
    environment,
    created_at::date,
    COALESCE(platform, 'unknown'),
    COALESCE(output_type, 'unknown');

CREATE VIEW analytics_cache_daily AS
SELECT
    environment,
    created_at::date AS activity_date,
    COUNT(*) FILTER (WHERE event_type = 'telegram_cache_hit') AS hit_count,
    COUNT(*) FILTER (WHERE event_type = 'telegram_cache_miss') AS miss_count,
    COUNT(*) AS lookup_count,
    CASE
        WHEN COUNT(*) = 0 THEN 0
        ELSE COUNT(*) FILTER (WHERE event_type = 'telegram_cache_hit')::numeric / COUNT(*)
    END AS hit_rate
FROM analytics_events
WHERE event_type IN ('telegram_cache_hit', 'telegram_cache_miss')
GROUP BY environment, created_at::date;

CREATE VIEW analytics_failures_daily AS
SELECT
    environment,
    created_at::date AS activity_date,
    COALESCE(platform, 'unknown') AS platform,
    COALESCE(output_type, 'unknown') AS output_type,
    COALESCE(error_code, 'unknown') AS error_code,
    COUNT(*) AS failure_count
FROM analytics_events
WHERE event_type IN ('download_failed', 'download_rejected', 'preflight_rejected')
GROUP BY
    environment,
    created_at::date,
    COALESCE(platform, 'unknown'),
    COALESCE(output_type, 'unknown'),
    COALESCE(error_code, 'unknown');

CREATE VIEW analytics_duration_buckets AS
SELECT
    environment,
    created_at::date AS activity_date,
    COALESCE(platform, 'unknown') AS platform,
    COALESCE(output_type, 'unknown') AS output_type,
    CASE
        WHEN source_duration_seconds < 60 THEN '<1m'
        WHEN source_duration_seconds < 300 THEN '1-5m'
        WHEN source_duration_seconds < 900 THEN '5-15m'
        WHEN source_duration_seconds < 3600 THEN '15-60m'
        ELSE '60m+'
    END AS duration_bucket,
    COUNT(*) AS item_count
FROM analytics_events
WHERE event_type = 'metadata_extracted'
  AND source_duration_seconds IS NOT NULL
GROUP BY
    environment,
    created_at::date,
    COALESCE(platform, 'unknown'),
    COALESCE(output_type, 'unknown'),
    CASE
        WHEN source_duration_seconds < 60 THEN '<1m'
        WHEN source_duration_seconds < 300 THEN '1-5m'
        WHEN source_duration_seconds < 900 THEN '5-15m'
        WHEN source_duration_seconds < 3600 THEN '15-60m'
        ELSE '60m+'
    END;
