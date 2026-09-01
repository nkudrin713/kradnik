ALTER TABLE download_choice_sessions
    ALTER COLUMN telegram_menu_message_id DROP NOT NULL,
    ADD COLUMN telegram_inline_message_id TEXT;

ALTER TABLE download_choice_sessions
    ADD CONSTRAINT download_choice_sessions_message_address_check
        CHECK ((telegram_menu_message_id IS NOT NULL) <> (telegram_inline_message_id IS NOT NULL));

ALTER TABLE download_jobs
    ADD COLUMN telegram_inline_message_id TEXT;
