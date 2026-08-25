ALTER TABLE download_jobs
    DROP CONSTRAINT download_jobs_output_type_check;

ALTER TABLE download_jobs
    ADD CONSTRAINT download_jobs_output_type_check
        CHECK (output_type IN ('video', 'audio', 'cover'));

ALTER TABLE analytics_events
    DROP CONSTRAINT analytics_events_output_type_check;

ALTER TABLE analytics_events
    ADD CONSTRAINT analytics_events_output_type_check
        CHECK (output_type IS NULL OR output_type IN ('video', 'audio', 'cover'));

CREATE TABLE download_choice_sessions
(
    token                       UUID PRIMARY KEY,
    telegram_user_id            BIGINT      NOT NULL,
    telegram_chat_id            BIGINT      NOT NULL,
    telegram_update_id          INTEGER     NOT NULL,
    telegram_request_message_id INTEGER     NOT NULL,
    telegram_menu_message_id    INTEGER     NOT NULL,
    original_url                TEXT        NOT NULL,
    normalized_url              TEXT        NOT NULL,
    options_json                TEXT        NOT NULL,
    expires_at                  TIMESTAMPTZ NOT NULL,
    selected_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_download_choice_sessions_expires_at
    ON download_choice_sessions (expires_at);
