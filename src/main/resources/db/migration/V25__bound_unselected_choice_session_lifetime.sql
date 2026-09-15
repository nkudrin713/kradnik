CREATE INDEX idx_download_choice_sessions_unselected_created_at
    ON download_choice_sessions (created_at)
    WHERE selected_at IS NULL;
