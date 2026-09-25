ALTER TABLE download_jobs DROP CONSTRAINT download_jobs_status_check;
ALTER TABLE download_jobs ADD CONSTRAINT download_jobs_status_check
    CHECK (status IN ('queued', 'processing', 'completed', 'failed', 'cancelled_by_user'));
