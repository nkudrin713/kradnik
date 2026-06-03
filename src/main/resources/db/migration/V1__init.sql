CREATE TABLE download_jobs
(
    id                          BIGSERIAL PRIMARY KEY,

    telegram_user_id            BIGINT      NOT NULL,
    telegram_chat_id            BIGINT      NOT NULL,

    original_url                TEXT        NOT NULL,
    normalized_url              TEXT        NOT NULL,

    output_type                 TEXT        NOT NULL DEFAULT 'video'
        CHECK (output_type IN ('video', 'audio')),

    status                      TEXT        NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'uploading', 'completed', 'failed')),

    attempts                    INTEGER     NOT NULL DEFAULT 0,

    source_title                TEXT,
    source_extractor            TEXT,
    source_duration_seconds     INTEGER,

    download_preset             TEXT,
    selected_format             TEXT,

    downloaded_file_size        BIGINT,

    telegram_file_id            TEXT,
    telegram_file_size          BIGINT,

    error_message               TEXT,

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),

    processing_started_at       TIMESTAMPTZ,
    uploading_started_at        TIMESTAMPTZ,
    downloaded_at               TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ
);

CREATE INDEX idx_download_jobs_queue
    ON download_jobs (created_at) WHERE status = 'queued';

CREATE INDEX idx_download_jobs_processing_recovery
    ON download_jobs (updated_at) WHERE status IN ('processing', 'uploading');

CREATE INDEX idx_download_jobs_completed_cache_lookup
    ON download_jobs (normalized_url, output_type, completed_at DESC) WHERE status = 'completed'
      AND telegram_file_id IS NOT NULL;

CREATE INDEX idx_download_jobs_chat_created_at
    ON download_jobs (telegram_chat_id, created_at DESC);


CREATE TABLE download_settings
(
    chat_id    BIGINT PRIMARY KEY,

    mode       TEXT        NOT NULL DEFAULT 'video'
        CHECK (mode IN ('video', 'audio')),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
