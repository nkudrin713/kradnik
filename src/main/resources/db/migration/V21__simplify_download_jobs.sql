ALTER TABLE download_jobs
    DROP COLUMN source_title,
    DROP COLUMN source_extractor,
    DROP COLUMN telegram_file_size,
    DROP COLUMN processing_started_at,
    DROP COLUMN uploading_started_at,
    DROP COLUMN downloaded_at;
