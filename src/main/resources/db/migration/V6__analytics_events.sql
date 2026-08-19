CREATE TABLE analytics_events
(
    id                      BIGSERIAL PRIMARY KEY,

    environment             TEXT        NOT NULL
        CHECK (environment IN ('prod', 'test')),

    event_type              TEXT        NOT NULL,

    job_id                  BIGINT,

    telegram_user_id        BIGINT,
    telegram_chat_id        BIGINT,

    platform                TEXT,

    output_type             TEXT
        CHECK (output_type IS NULL OR output_type IN ('video', 'audio')),

    cache_key               TEXT,

    source_duration_seconds INTEGER,

    downloaded_file_size    BIGINT,
    telegram_file_size      BIGINT,

    success                 BOOLEAN,
    error_code              TEXT,

    properties              JSONB       NOT NULL DEFAULT '{}'::jsonb,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_analytics_events_created_at
    ON analytics_events (created_at);

CREATE INDEX idx_analytics_events_environment_created_at
    ON analytics_events (environment, created_at);

CREATE INDEX idx_analytics_events_event_type_created_at
    ON analytics_events (event_type, created_at);

CREATE INDEX idx_analytics_events_job_id
    ON analytics_events (job_id);

CREATE INDEX idx_analytics_events_user_created_at
    ON analytics_events (telegram_user_id, created_at);
