CREATE TABLE user_activity_days (
    day DATE NOT NULL,
    telegram_user_id BIGINT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (day, telegram_user_id),
    CHECK (first_seen_at <= last_seen_at)
);

-- Existing jobs are the only historical evidence of user activity.
INSERT INTO user_activity_days (day, telegram_user_id, first_seen_at, last_seen_at)
SELECT (created_at AT TIME ZONE 'UTC')::date, telegram_user_id, min(created_at), max(created_at)
FROM download_jobs
GROUP BY 1, 2;
