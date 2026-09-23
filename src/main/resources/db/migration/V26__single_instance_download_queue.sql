-- Stop the old application before applying this single-instance queue migration.
UPDATE download_jobs SET status = 'queued' WHERE status IN ('processing', 'uploading');
ALTER TABLE download_jobs DROP CONSTRAINT download_jobs_status_check;
ALTER TABLE download_jobs ADD CONSTRAINT download_jobs_status_check
    CHECK (status IN ('queued', 'processing', 'completed', 'failed'));
DROP INDEX idx_download_jobs_lease_recovery;
ALTER TABLE download_jobs
    DROP COLUMN lease_token,
    DROP COLUMN lease_expires_at,
    DROP COLUMN next_attempt_at,
    DROP COLUMN attempts,
    DROP COLUMN source_duration_seconds,
    DROP COLUMN source_audio_title,
    DROP COLUMN source_audio_performer,
    ADD COLUMN started_at TIMESTAMPTZ;
