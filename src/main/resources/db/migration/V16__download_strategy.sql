ALTER TABLE download_jobs
    ADD COLUMN download_strategy TEXT;

UPDATE download_jobs
SET download_strategy = CASE
    WHEN output_type = 'cover' AND download_preset LIKE 'instagram_%' THEN 'cover_instagram_embed'
    WHEN output_type = 'cover' THEN 'cover_yt_dlp'
    WHEN download_preset LIKE 'instagram_%' THEN 'instagram_embed'
    WHEN download_preset LIKE 'vk_%' THEN 'vk_yt_dlp'
    WHEN download_preset LIKE 'youtube_%' THEN 'youtube_yt_dlp'
    ELSE 'yt_dlp'
END;

ALTER TABLE download_jobs
    ALTER COLUMN download_strategy SET NOT NULL,
    ADD CONSTRAINT download_jobs_strategy_check CHECK (
        download_strategy IN (
            'yt_dlp',
            'youtube_yt_dlp',
            'vk_yt_dlp',
            'instagram_embed',
            'cover_yt_dlp',
            'cover_instagram_embed'
        )
    );

UPDATE download_choice_sessions
SET options_json = (
    SELECT COALESCE(
        jsonb_agg(
            option || jsonb_build_object(
                'strategy',
                CASE
                    WHEN option ->> 'outputType' = 'COVER' AND option ->> 'presetName' LIKE 'instagram_%'
                        THEN 'COVER_INSTAGRAM_EMBED'
                    WHEN option ->> 'outputType' = 'COVER' THEN 'COVER_YT_DLP'
                    WHEN option ->> 'presetName' LIKE 'instagram_%' THEN 'INSTAGRAM_EMBED'
                    WHEN option ->> 'presetName' LIKE 'vk_%' THEN 'VK_YT_DLP'
                    WHEN option ->> 'presetName' LIKE 'youtube_%' THEN 'YOUTUBE_YT_DLP'
                    ELSE 'YT_DLP'
                END
            )
        ),
        '[]'::jsonb
    )::TEXT
    FROM jsonb_array_elements(options_json::jsonb) AS option
);
