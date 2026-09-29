CREATE INDEX idx_download_jobs_processing_user
    ON download_jobs (telegram_user_id)
    WHERE status = 'processing';
