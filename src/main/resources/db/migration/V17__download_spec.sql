UPDATE download_choice_sessions
SET options_json = (
    SELECT COALESCE(
        jsonb_agg(
            (
                option
                - 'originalUrl'
                - 'normalizedUrl'
                - 'cacheKey'
                - 'outputType'
                - 'strategy'
                - 'presetName'
                - 'formatSelector'
                - 'extraArgs'
            ) || jsonb_build_object(
                    'spec',
                    jsonb_build_object(
                        'originalUrl', option -> 'originalUrl',
                        'normalizedUrl', option -> 'normalizedUrl',
                        'cacheKey', option -> 'cacheKey',
                        'outputType', option -> 'outputType',
                        'strategy', option -> 'strategy',
                        'presetName', option -> 'presetName',
                        'formatSelector', option -> 'formatSelector',
                        'extraArgs', option -> 'extraArgs'
                    )
                )
        ),
        '[]'::jsonb
    )::TEXT
    FROM jsonb_array_elements(options_json::jsonb) AS option
);

ALTER TABLE download_choice_sessions
    DROP COLUMN original_url,
    DROP COLUMN normalized_url;
