ALTER TABLE download_jobs
    ADD COLUMN workload_type VARCHAR(32) NOT NULL DEFAULT 'single',
    ADD COLUMN playlist_entries_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN playlist_results_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE download_jobs ADD CONSTRAINT download_jobs_workload_type_check
    CHECK (workload_type IN ('single', 'playlist_audio'));

CREATE INDEX idx_download_jobs_workload_queue
    ON download_jobs (workload_type, created_at, id)
    WHERE status = 'queued';
