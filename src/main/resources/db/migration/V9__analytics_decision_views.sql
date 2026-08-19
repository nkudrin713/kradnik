CREATE VIEW analytics_user_retention_daily AS
WITH daily_users AS (
    SELECT DISTINCT
        environment,
        created_at::date AS activity_date,
        telegram_user_id
    FROM analytics_events
    WHERE telegram_user_id IS NOT NULL
      AND event_type = 'download_requested'
),
first_seen AS (
    SELECT
        environment,
        telegram_user_id,
        MIN(activity_date) AS first_seen_date
    FROM daily_users
    GROUP BY environment, telegram_user_id
),
active_daily AS (
    SELECT
        current_day.environment,
        current_day.activity_date,
        COUNT(*) AS active_users,
        COUNT(*) FILTER (
            WHERE first_seen.first_seen_date = current_day.activity_date
        ) AS new_users,
        COUNT(*) FILTER (
            WHERE first_seen.first_seen_date < current_day.activity_date
              AND previous_day.telegram_user_id IS NOT NULL
        ) AS retained_users,
        COUNT(*) FILTER (
            WHERE first_seen.first_seen_date < current_day.activity_date
              AND previous_day.telegram_user_id IS NULL
        ) AS resurrected_users
    FROM daily_users current_day
    JOIN first_seen
      ON first_seen.environment = current_day.environment
     AND first_seen.telegram_user_id = current_day.telegram_user_id
    LEFT JOIN daily_users previous_day
      ON previous_day.environment = current_day.environment
     AND previous_day.telegram_user_id = current_day.telegram_user_id
     AND previous_day.activity_date = current_day.activity_date - INTERVAL '1 day'
    GROUP BY current_day.environment, current_day.activity_date
),
churn_daily AS (
    SELECT
        previous_day.environment,
        previous_day.activity_date + INTERVAL '1 day' AS activity_date,
        COUNT(*) AS churned_users
    FROM daily_users previous_day
    LEFT JOIN daily_users current_day
      ON current_day.environment = previous_day.environment
     AND current_day.telegram_user_id = previous_day.telegram_user_id
     AND current_day.activity_date = previous_day.activity_date + INTERVAL '1 day'
    WHERE current_day.telegram_user_id IS NULL
    GROUP BY previous_day.environment, previous_day.activity_date + INTERVAL '1 day'
)
SELECT
    active_daily.environment,
    active_daily.activity_date,
    active_daily.active_users,
    active_daily.new_users,
    active_daily.retained_users,
    active_daily.resurrected_users,
    COALESCE(churn_daily.churned_users, 0) AS churned_users
FROM active_daily
LEFT JOIN churn_daily
  ON churn_daily.environment = active_daily.environment
 AND churn_daily.activity_date::date = active_daily.activity_date;

CREATE VIEW analytics_job_lifecycle AS
SELECT
    environment,
    job_id,
    MAX(telegram_user_id) AS telegram_user_id,
    MAX(telegram_chat_id) AS telegram_chat_id,
    MAX(platform) AS platform,
    MAX(output_type) AS output_type,
    MAX(cache_key) AS cache_key,
    MIN(created_at) FILTER (WHERE event_type = 'download_requested') AS requested_at,
    MIN(created_at) FILTER (WHERE event_type = 'telegram_cache_hit') AS cache_hit_at,
    MIN(created_at) FILTER (WHERE event_type = 'telegram_cache_miss') AS cache_miss_at,
    MIN(created_at) FILTER (WHERE event_type = 'metadata_extracted') AS metadata_extracted_at,
    MIN(created_at) FILTER (WHERE event_type = 'download_started') AS download_started_at,
    MIN(created_at) FILTER (WHERE event_type = 'upload_started') AS upload_started_at,
    MIN(created_at) FILTER (WHERE event_type = 'download_completed') AS completed_at,
    MIN(created_at) FILTER (WHERE event_type = 'download_failed') AS failed_at,
    MIN(created_at) FILTER (WHERE event_type = 'download_rejected') AS rejected_at,
    BOOL_OR(event_type = 'telegram_cache_hit') AS cache_hit,
    BOOL_OR(event_type = 'download_completed') AS completed,
    BOOL_OR(event_type = 'download_failed') AS failed,
    BOOL_OR(event_type = 'download_rejected') AS rejected,
    MAX(source_duration_seconds) AS source_duration_seconds,
    MAX(downloaded_file_size) AS downloaded_file_size,
    MAX(telegram_file_size) AS telegram_file_size,
    MAX(error_code) FILTER (WHERE error_code IS NOT NULL) AS error_code,
    EXTRACT(
        EPOCH FROM (
            MIN(created_at) FILTER (WHERE event_type = 'download_started')
            - MIN(created_at) FILTER (WHERE event_type = 'download_requested')
        )
    ) AS queue_wait_seconds,
    EXTRACT(
        EPOCH FROM (
            MIN(created_at) FILTER (WHERE event_type = 'upload_started')
            - MIN(created_at) FILTER (WHERE event_type = 'download_started')
        )
    ) AS download_seconds,
    EXTRACT(
        EPOCH FROM (
            MIN(created_at) FILTER (WHERE event_type = 'download_completed')
            - MIN(created_at) FILTER (WHERE event_type = 'upload_started')
        )
    ) AS upload_seconds,
    EXTRACT(
        EPOCH FROM (
            COALESCE(
                MIN(created_at) FILTER (WHERE event_type = 'download_completed'),
                MIN(created_at) FILTER (WHERE event_type = 'download_failed'),
                MIN(created_at) FILTER (WHERE event_type = 'download_rejected')
            )
            - MIN(created_at) FILTER (WHERE event_type = 'download_requested')
        )
    ) AS total_seconds
FROM analytics_events
WHERE job_id IS NOT NULL
GROUP BY environment, job_id;

CREATE VIEW analytics_job_latency_daily AS
SELECT
    environment,
    requested_at::date AS activity_date,
    COALESCE(platform, 'unknown') AS platform,
    COALESCE(output_type, 'unknown') AS output_type,
    COUNT(*) AS job_count,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY queue_wait_seconds) AS queue_wait_p50_seconds,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY queue_wait_seconds) AS queue_wait_p95_seconds,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY download_seconds) AS download_p50_seconds,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY download_seconds) AS download_p95_seconds,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY upload_seconds) AS upload_p50_seconds,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY upload_seconds) AS upload_p95_seconds,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY total_seconds) AS total_p50_seconds,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY total_seconds) AS total_p95_seconds
FROM analytics_job_lifecycle
WHERE requested_at IS NOT NULL
GROUP BY
    environment,
    requested_at::date,
    COALESCE(platform, 'unknown'),
    COALESCE(output_type, 'unknown');
