ALTER TABLE download_settings
    DROP CONSTRAINT download_settings_mode_check;

ALTER TABLE download_settings
    ALTER COLUMN mode SET DEFAULT 'ask';

ALTER TABLE download_settings
    ADD CONSTRAINT download_settings_mode_check
        CHECK (mode IN ('video', 'audio', 'ask'));

ALTER TABLE download_settings
    ADD COLUMN mode_menu_message_id INTEGER;

ALTER TABLE download_jobs
    ADD COLUMN telegram_request_message_id INTEGER;
