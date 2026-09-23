package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DownloadJobRepository : JpaRepository<DownloadJob, Long> {
    fun findByTelegramUpdateId(telegramUpdateId: Int): DownloadJob?

    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(CAST(:telegramUpdateId AS BIGINT))) AS locked", nativeQuery = true)
    fun lockTelegramUpdate(telegramUpdateId: Int): Int

    @Query(value = """
        WITH picked AS (
            SELECT id FROM download_jobs
            WHERE status = 'queued'
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        UPDATE download_jobs
        SET status = 'processing', started_at = now(), updated_at = now()
        FROM picked
        WHERE download_jobs.id = picked.id
        RETURNING download_jobs.*
    """, nativeQuery = true)
    fun claimNextQueuedJob(): DownloadJob?

    @Modifying
    @Query(value = """
        UPDATE download_jobs
        SET status = 'completed', telegram_file_id = :telegramFileId,
            error_message = NULL, completed_at = now(), updated_at = now()
        WHERE id = :jobId AND status = 'processing'
    """, nativeQuery = true)
    fun complete(jobId: Long, telegramFileId: String): Int

    @Modifying
    @Query(value = """
        UPDATE download_jobs
        SET status = 'failed', error_message = :errorMessage,
            completed_at = now(), updated_at = now()
        WHERE id = :jobId AND status = 'processing'
    """, nativeQuery = true)
    fun fail(jobId: Long, errorMessage: String): Int

    @Modifying
    @Query(value = """
        UPDATE download_jobs
        SET status = 'queued', started_at = NULL, updated_at = now()
        WHERE status = 'processing'
    """, nativeQuery = true)
    fun requeueProcessingJobs(): Int

    @Query(value = """
        SELECT * FROM download_jobs
        WHERE cache_key = :cacheKey AND status = 'completed' AND telegram_file_id IS NOT NULL
        ORDER BY completed_at DESC LIMIT 1
    """, nativeQuery = true)
    fun findCachedCompletedJob(cacheKey: String): DownloadJob?
}
