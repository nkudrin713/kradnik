ALTER TABLE download_jobs
    DROP CONSTRAINT download_jobs_strategy_check;

ALTER TABLE download_jobs
    RENAME COLUMN download_strategy TO platform;

UPDATE download_jobs
SET platform = CASE
    WHEN platform IN ('instagram_embed', 'cover_instagram_embed') THEN 'instagram'
    WHEN platform = 'vk_yt_dlp' OR download_preset LIKE 'vk_%' THEN 'vk'
    ELSE 'youtube'
END;

ALTER TABLE download_jobs
    ADD CONSTRAINT download_jobs_platform_check
        CHECK (platform IN ('youtube', 'instagram', 'vk'));

UPDATE download_choice_sessions
SET options_json = (
    SELECT COALESCE(
        jsonb_agg(
            jsonb_set(
                option,
                '{spec}',
                ((option -> 'spec') - 'strategy') || jsonb_build_object(
                    'platform',
                    CASE
                        WHEN option -> 'spec' ->> 'strategy' IN ('INSTAGRAM_EMBED', 'COVER_INSTAGRAM_EMBED')
                            THEN 'INSTAGRAM'
                        WHEN option -> 'spec' ->> 'strategy' = 'VK_YT_DLP'
                             OR option -> 'spec' ->> 'presetName' LIKE 'vk_%'
                            THEN 'VK'
                        ELSE 'YOUTUBE'
                    END
                )
            )
        ),
        '[]'::jsonb
    )::TEXT
    FROM jsonb_array_elements(options_json::jsonb) AS option
);
