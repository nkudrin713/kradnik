CREATE TABLE admin_memory_minutes (
    minute TIMESTAMPTZ PRIMARY KEY,
    sampled_at TIMESTAMPTZ NOT NULL,
    heap_used BIGINT NOT NULL,
    heap_committed BIGINT NOT NULL,
    heap_max BIGINT,
    non_heap_used BIGINT NOT NULL,
    metaspace_used BIGINT,
    code_cache_used BIGINT,
    container_used BIGINT,
    container_limit BIGINT,
    gc_count BIGINT,
    gc_time_millis BIGINT,
    gc_window_seconds BIGINT NOT NULL
);
