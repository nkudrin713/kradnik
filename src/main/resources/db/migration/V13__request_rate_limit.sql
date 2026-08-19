ALTER TABLE download_jobs
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_download_jobs_queued_next_attempt
    ON download_jobs (next_attempt_at, created_at)
    WHERE status = 'queued';

CREATE TABLE request_rate_limit_buckets
(
    provider                VARCHAR(64)  NOT NULL,
    operation               VARCHAR(64)  NOT NULL,
    scope                   VARCHAR(128) NOT NULL,
    next_allowed_at         TIMESTAMPTZ  NOT NULL DEFAULT to_timestamp(0),
    cooldown_until          TIMESTAMPTZ,
    consecutive_throttles   INTEGER      NOT NULL DEFAULT 0,
    last_request_at         TIMESTAMPTZ,
    last_success_at         TIMESTAMPTZ,
    last_throttle_at        TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, operation, scope),
    CHECK (consecutive_throttles >= 0)
);
