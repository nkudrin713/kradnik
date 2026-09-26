-- Keep dashboard scans away from historical request payloads.
CREATE INDEX idx_download_jobs_admin_active
    ON download_jobs (workload_type, status, created_at)
    WHERE status IN ('queued', 'processing');

CREATE INDEX idx_download_jobs_admin_outcomes
    ON download_jobs (completed_at, workload_type, status)
    WHERE status IN ('completed', 'failed', 'cancelled_by_user');

CREATE TABLE admin_error_minutes (
    minute TIMESTAMPTZ NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    count BIGINT NOT NULL CHECK (count >= 0),
    PRIMARY KEY (minute, instance_id, kind)
);

CREATE TABLE admin_queue_minutes (
    minute TIMESTAMPTZ PRIMARY KEY,
    sampled_at TIMESTAMPTZ NOT NULL,
    queued BIGINT NOT NULL CHECK (queued >= 0),
    processing BIGINT NOT NULL CHECK (processing >= 0)
);
