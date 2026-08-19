ALTER TABLE download_jobs
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

UPDATE download_jobs
SET lease_expires_at = now()
WHERE status IN ('processing', 'uploading');

DROP INDEX idx_download_jobs_processing_recovery;

CREATE INDEX idx_download_jobs_lease_recovery
    ON download_jobs (lease_expires_at)
    WHERE status IN ('processing', 'uploading');
