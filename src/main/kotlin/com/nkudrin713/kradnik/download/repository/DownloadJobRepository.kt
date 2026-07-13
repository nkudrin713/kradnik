package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface DownloadJobRepository : JpaRepository<DownloadJob, Long> {
	fun findByTelegramUpdateId(telegramUpdateId: Int): DownloadJob?

	@Query(
		value = """
            WITH picked AS (
                SELECT id
                FROM download_jobs
                WHERE status = 'queued'
                  AND attempts < :maxAttempts
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE download_jobs
            SET status = 'processing',
                attempts = attempts + 1,
                processing_started_at = now(),
                lease_token = :leaseToken,
                lease_expires_at = :leaseExpiresAt,
                updated_at = now()
            FROM picked
            WHERE download_jobs.id = picked.id
            RETURNING download_jobs.*
        """,
		nativeQuery = true,
	)
	fun claimNextQueuedJob(
		maxAttempts: Int,
		leaseToken: UUID,
		leaseExpiresAt: Instant,
	): DownloadJob?

	@Modifying
	@Query(
		value = """
			UPDATE download_jobs
			SET lease_expires_at = :leaseExpiresAt,
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status IN ('processing', 'uploading')
		""",
		nativeQuery = true,
	)
	fun renewLease(
		jobId: Long,
		leaseToken: UUID,
		leaseExpiresAt: Instant,
	): Int

	@Modifying
	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'queued',
			    error_message = 'Recovered stale in-progress job',
			    lease_token = NULL,
			    lease_expires_at = NULL,
			    updated_at = now()
			WHERE status IN ('processing', 'uploading')
			  AND (lease_expires_at IS NULL OR lease_expires_at < :expiredBefore)
			  AND attempts < :maxAttempts
		""",
		nativeQuery = true,
	)
	fun requeueStaleInProgressJobs(
		expiredBefore: Instant,
		maxAttempts: Int,
	): Int

	@Modifying
	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'failed',
			    error_message = 'Failed after stale in-progress recovery',
			    completed_at = now(),
			    lease_token = NULL,
			    lease_expires_at = NULL,
			    updated_at = now()
			WHERE status IN ('processing', 'uploading')
			  AND (lease_expires_at IS NULL OR lease_expires_at < :expiredBefore)
			  AND attempts >= :maxAttempts
		""",
		nativeQuery = true,
	)
	fun failStaleInProgressJobs(
		expiredBefore: Instant,
		maxAttempts: Int,
	): Int

	@Query(
		value = """
			SELECT *
			FROM download_jobs
			WHERE cache_key = :cacheKey
			  AND status = 'completed'
			  AND telegram_file_id IS NOT NULL
			ORDER BY completed_at DESC
			LIMIT 1
		""",
		nativeQuery = true,
	)
	fun findCachedCompletedJob(
		cacheKey: String,
	): DownloadJob?
}
