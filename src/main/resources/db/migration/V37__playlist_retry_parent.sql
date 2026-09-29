ALTER TABLE download_jobs ADD COLUMN retry_of_job_id BIGINT;

CREATE UNIQUE INDEX idx_download_jobs_retry_parent
    ON download_jobs (retry_of_job_id)
    WHERE retry_of_job_id IS NOT NULL;
