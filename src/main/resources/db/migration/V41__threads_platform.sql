ALTER TABLE download_jobs
    DROP CONSTRAINT download_jobs_platform_check,
    ADD CONSTRAINT download_jobs_platform_check
        CHECK (platform IN ('youtube', 'instagram', 'vk', 'threads'));
