package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DownloadJobRepository : JpaRepository<DownloadJob, Long> {
    fun findByTelegramUpdateId(telegramUpdateId: Int): DownloadJob?

    fun findByRetryOfJobId(retryOfJobId: Long): DownloadJob?

    /** Serializes short claims from both worker pools before checking a user's processing jobs. */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(7262824436563968)) AS locked", nativeQuery = true)
    fun lockQueueClaims(): Int

    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(CAST(:telegramUpdateId AS BIGINT))) AS locked", nativeQuery = true)
    fun lockTelegramUpdate(telegramUpdateId: Int): Int

    @Query(
        value = """
        WITH picked AS (
            SELECT queued.id FROM download_jobs queued
            WHERE queued.status = 'queued' AND queued.workload_type = 'single'
              AND NOT EXISTS (
                  SELECT 1 FROM download_jobs active
                  WHERE active.telegram_user_id = queued.telegram_user_id AND active.status = 'processing'
              )
            ORDER BY queued.created_at, queued.id
            FOR UPDATE OF queued SKIP LOCKED
            LIMIT 1
        )
        UPDATE download_jobs
        SET status = 'processing', started_at = now(), updated_at = now()
        FROM picked
        WHERE download_jobs.id = picked.id
        RETURNING download_jobs.*
    """,
        nativeQuery = true,
    )
    fun claimNextQueuedJob(): DownloadJob?

    @Query(
        value = """
        WITH picked AS (
            SELECT queued.id FROM download_jobs queued
            WHERE queued.status = 'queued' AND queued.workload_type = 'playlist_audio'
              AND NOT EXISTS (
                  SELECT 1 FROM download_jobs active
                  WHERE active.telegram_user_id = queued.telegram_user_id AND active.status = 'processing'
              )
            ORDER BY queued.created_at, queued.id
            FOR UPDATE OF queued SKIP LOCKED
            LIMIT 1
        )
        UPDATE download_jobs
        SET status = 'processing', started_at = now(), updated_at = now()
        FROM picked
        WHERE download_jobs.id = picked.id
        RETURNING download_jobs.*
    """,
        nativeQuery = true,
    )
    fun claimNextQueuedPlaylistJob(): DownloadJob?

    @Query(
        value = """
        SELECT (
            SELECT COUNT(*) FROM download_jobs preceding
            WHERE preceding.status = 'queued' AND preceding.workload_type = target.workload_type
              AND (preceding.created_at, preceding.id) < (target.created_at, target.id)
        ) + 1
        FROM download_jobs target
        WHERE target.id = :jobId AND target.status = 'queued'
        """,
        nativeQuery = true,
    )
    fun queuePosition(jobId: Long): Long?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM DownloadJob job WHERE job.id = :jobId")
    fun findForUpdate(jobId: Long): DownloadJob?

    @Modifying
    @Query(
        value = """
        UPDATE download_jobs
        SET status = 'cancelled_by_user', completed_at = now(), updated_at = now(),
            error_message = NULL
        WHERE id = :jobId AND telegram_user_id = :telegramUserId
          AND status IN ('queued', 'processing')
    """,
        nativeQuery = true,
    )
    fun cancelByUser(jobId: Long, telegramUserId: Long): Int

    @Modifying
    @Query(
        value = """
        UPDATE download_jobs
        SET status = 'completed', telegram_file_id = :telegramFileId,
            error_message = NULL, completed_at = now(), updated_at = now()
        WHERE id = :jobId AND status = 'processing'
    """,
        nativeQuery = true,
    )
    fun complete(jobId: Long, telegramFileId: String): Int

    @Modifying
    @Query(
        value = """
        UPDATE download_jobs
        SET status = 'failed', error_message = :errorMessage,
            completed_at = now(), updated_at = now()
        WHERE id = :jobId AND status = 'processing'
    """,
        nativeQuery = true,
    )
    fun fail(jobId: Long, errorMessage: String): Int

    @Modifying
    @Query(
        value = """
        UPDATE download_jobs
        SET status = 'queued', started_at = NULL, updated_at = now()
        WHERE status = 'processing'
    """,
        nativeQuery = true,
    )
    fun requeueProcessingJobs(): Int

    @Query(
        value = """
        SELECT * FROM download_jobs
        WHERE cache_key = :cacheKey AND status = 'completed' AND telegram_file_id IS NOT NULL
        ORDER BY completed_at DESC LIMIT 1
    """,
        nativeQuery = true,
    )
    fun findCachedCompletedJob(cacheKey: String): DownloadJob?

    @Query(
        value = """
        SELECT result->>'fileId'
        FROM download_jobs job
        CROSS JOIN LATERAL jsonb_array_elements(job.playlist_entries_json::jsonb) entry
        CROSS JOIN LATERAL jsonb_array_elements(job.playlist_results_json::jsonb) result
        WHERE job.status = 'completed' AND job.workload_type = 'playlist_audio'
          AND job.download_preset = :preset AND job.selected_format = :format
          AND job.download_extra_args::jsonb = CAST(:args AS jsonb)
          AND entry->>'videoId' = :videoId AND result->>'position' = entry->>'position'
          AND result->>'fileId' IS NOT NULL
        ORDER BY job.completed_at DESC LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findCachedPlaylistFile(videoId: String, preset: String, format: String, args: String): String?
}
