CREATE TABLE download_tasks (
    id BIGSERIAL PRIMARY KEY,

    telegram_user_id BIGINT NOT NULL,
    telegram_chat_id BIGINT NOT NULL,

    original_url TEXT NOT NULL,
    normalized_url TEXT NOT NULL,

    output_type TEXT NOT NULL DEFAULT 'video'
        CHECK (output_type IN ('video', 'audio')),

    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'uploading', 'completed', 'failed')),

    source_title TEXT,
    source_extractor TEXT,
    source_duration_seconds INTEGER,
    source_format_id TEXT,
    source_ext TEXT,
    source_width INTEGER,
    source_height INTEGER,
    source_fps NUMERIC(6,2),
    source_filesize BIGINT,

    output_ext TEXT,
    output_audio_codec TEXT,
    output_video_codec TEXT,
    output_bitrate TEXT,
    output_args JSONB,

    telegram_file_id TEXT,
    telegram_file_size BIGINT,

    error_message TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_download_tasks_status_created_at
    ON download_tasks (status, created_at);

CREATE INDEX idx_download_tasks_cache_lookup
    ON download_tasks (normalized_url, output_type);

CREATE TABLE download_settings (
    chat_id BIGINT PRIMARY KEY,

    mode TEXT NOT NULL DEFAULT 'video'
        CHECK (mode IN ('video', 'audio')),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
