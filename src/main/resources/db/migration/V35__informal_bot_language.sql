ALTER TABLE telegram_user_preferences
    DROP CONSTRAINT telegram_user_preferences_language_check,
    ADD CONSTRAINT telegram_user_preferences_language_check
        CHECK (language IN ('en', 'ru', 'ru-informal'));

ALTER TABLE download_choice_sessions
    DROP CONSTRAINT download_choice_sessions_language_check,
    ADD CONSTRAINT download_choice_sessions_language_check
        CHECK (language IN ('en', 'ru', 'ru-informal'));

ALTER TABLE download_jobs
    DROP CONSTRAINT download_jobs_language_check,
    ADD CONSTRAINT download_jobs_language_check
        CHECK (language IN ('en', 'ru', 'ru-informal'));
