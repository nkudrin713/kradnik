ALTER TABLE download_jobs
    DROP CONSTRAINT download_jobs_output_type_check;

ALTER TABLE download_jobs
    ADD CONSTRAINT download_jobs_output_type_check
        CHECK (output_type IN ('video', 'audio', 'cover', 'images', 'post'));
