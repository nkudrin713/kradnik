ALTER TABLE download_jobs
    ADD COLUMN cache_key TEXT;

UPDATE download_jobs
SET cache_key = 'generic:' || output_type || ':' || COALESCE(download_preset, 'default') || ':' || normalized_url;

ALTER TABLE download_jobs
    ALTER COLUMN cache_key SET NOT NULL;

CREATE INDEX idx_download_jobs_completed_cache_key_lookup
    ON download_jobs (cache_key, completed_at DESC) WHERE status = 'completed'
      AND telegram_file_id IS NOT NULL;
