ALTER TABLE download_jobs
    ADD COLUMN telegram_update_id INTEGER;

CREATE UNIQUE INDEX idx_download_jobs_telegram_update_id
    ON download_jobs (telegram_update_id)
    WHERE telegram_update_id IS NOT NULL;
