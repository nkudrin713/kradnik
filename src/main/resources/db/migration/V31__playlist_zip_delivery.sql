ALTER TABLE download_jobs
    ADD COLUMN playlist_delivery_mode VARCHAR(32) NOT NULL DEFAULT 'AUDIO_MESSAGES',
    ADD COLUMN playlist_title TEXT;

ALTER TABLE download_jobs ADD CONSTRAINT download_jobs_playlist_delivery_mode_check
    CHECK (playlist_delivery_mode IN ('AUDIO_MESSAGES', 'ZIP'));
