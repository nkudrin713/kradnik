ALTER TABLE download_jobs
    ADD COLUMN download_extra_args TEXT NOT NULL DEFAULT '[]';

UPDATE download_jobs
SET download_preset = COALESCE(
    download_preset,
    CASE output_type
        WHEN 'audio' THEN 'default_audio'
        ELSE 'default_mobile_video'
    END
);

UPDATE download_jobs
SET selected_format = COALESCE(
    selected_format,
    CASE output_type
        WHEN 'audio' THEN 'ba/bestaudio'
        ELSE 'bv*[filesize<40M][height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/bv*[height<=720][filesize<40M][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/bv*[height<=480][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/b[height<=720][vcodec^=avc1][ext=mp4]/b'
    END
);

UPDATE download_jobs
SET download_extra_args = CASE
    WHEN output_type = 'audio' THEN '["-x","--audio-format","mp3"]'
    ELSE '["--merge-output-format","mp4"]'
END
WHERE download_extra_args = '[]';

ALTER TABLE download_jobs
    ALTER COLUMN download_preset SET NOT NULL,
    ALTER COLUMN selected_format SET NOT NULL;
