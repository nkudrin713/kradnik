CREATE TABLE telegram_user_preferences
(
    telegram_user_id BIGINT PRIMARY KEY,
    language         TEXT        NOT NULL DEFAULT 'en'
        CHECK (language IN ('en', 'ru')),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE download_choice_sessions
    ADD COLUMN language TEXT NOT NULL DEFAULT 'en'
        CHECK (language IN ('en', 'ru'));

ALTER TABLE download_jobs
    ADD COLUMN language TEXT NOT NULL DEFAULT 'en'
        CHECK (language IN ('en', 'ru'));
