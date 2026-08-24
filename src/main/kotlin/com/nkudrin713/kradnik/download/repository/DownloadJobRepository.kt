package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface DownloadJobRepository : JpaRepository<DownloadJob, Long> {
	fun findByTelegramUpdateId(telegramUpdateId: Int): DownloadJob?

	fun existsByIdAndLeaseToken(id: Long, leaseToken: UUID): Boolean

	@Query(
		value = """
			SELECT 1
			FROM (
				SELECT pg_advisory_xact_lock(CAST(:telegramUpdateId AS BIGINT))
			) AS locked
		""",
		nativeQuery = true,
	)
	fun lockTelegramUpdate(telegramUpdateId: Int): Int

	@Query(
		value = """
            WITH picked AS (
                SELECT id
                FROM download_jobs
                WHERE status = 'queued'
                  AND attempts < :maxAttempts
                  AND next_attempt_at <= now()
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE download_jobs
            SET status = 'processing',
                attempts = attempts + 1,
                lease_token = :leaseToken,
                lease_expires_at = now() + (:leaseDurationMs * INTERVAL '1 millisecond'),
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
			leaseDurationMs: Long,
	): DownloadJob?

	@Modifying
	@Query(
		value = """
				UPDATE download_jobs
				SET lease_expires_at = now() + (:leaseDurationMs * INTERVAL '1 millisecond'),
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
			leaseDurationMs: Long,
	): Int

	@Query(
		value = """
			UPDATE download_jobs
			SET source_duration_seconds = :sourceDurationSeconds,
			    source_audio_title = :sourceAudioTitle,
			    source_audio_performer = :sourceAudioPerformer,
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status = 'processing'
			RETURNING download_jobs.*
		""",
		nativeQuery = true,
	)
	fun updateOwnedMetadata(
		jobId: Long,
		leaseToken: UUID,
		sourceDurationSeconds: Int?,
		sourceAudioTitle: String?,
		sourceAudioPerformer: String?,
	): DownloadJob?

	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'uploading',
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status = 'processing'
			RETURNING download_jobs.*
		""",
		nativeQuery = true,
	)
	fun markOwnedUploading(
		jobId: Long,
		leaseToken: UUID,
	): DownloadJob?

	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'completed',
			    downloaded_file_size = :downloadedFileSize,
			    telegram_file_id = :telegramFileId,
			    error_message = NULL,
			    lease_token = NULL,
			    lease_expires_at = NULL,
			    completed_at = now(),
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status = 'uploading'
			RETURNING download_jobs.*
		""",
		nativeQuery = true,
	)
	fun markOwnedCompleted(
		jobId: Long,
		leaseToken: UUID,
		telegramFileId: String,
		downloadedFileSize: Long?,
	): DownloadJob?

	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'queued',
			    error_message = :errorMessage,
			    next_attempt_at = :nextAttemptAt,
			    lease_token = NULL,
			    lease_expires_at = NULL,
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status IN ('processing', 'uploading')
			RETURNING download_jobs.*
		""",
		nativeQuery = true,
	)
	fun requeueOwnedJob(
		jobId: Long,
		leaseToken: UUID,
		errorMessage: String,
		nextAttemptAt: Instant,
	): DownloadJob?

	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'queued',
			    attempts = GREATEST(attempts - 1, 0),
			    error_message = :reason,
			    next_attempt_at = :nextAttemptAt,
			    lease_token = NULL,
			    lease_expires_at = NULL,
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status = 'processing'
			RETURNING download_jobs.*
		""",
		nativeQuery = true,
	)
	fun deferOwnedJob(
		jobId: Long,
		leaseToken: UUID,
		reason: String,
		nextAttemptAt: Instant,
	): DownloadJob?

	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'failed',
			    error_message = :errorMessage,
			    completed_at = now(),
			    lease_token = NULL,
			    lease_expires_at = NULL,
			    updated_at = now()
			WHERE id = :jobId
			  AND lease_token = :leaseToken
			  AND status IN ('processing', 'uploading')
			RETURNING download_jobs.*
		""",
		nativeQuery = true,
	)
	fun failOwnedJob(
		jobId: Long,
		leaseToken: UUID,
		errorMessage: String,
	): DownloadJob?

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
				  AND (lease_expires_at IS NULL OR lease_expires_at < now())
			  AND attempts < :maxAttempts
		""",
		nativeQuery = true,
	)
	fun requeueStaleInProgressJobs(
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
				  AND (lease_expires_at IS NULL OR lease_expires_at < now())
			  AND attempts >= :maxAttempts
		""",
		nativeQuery = true,
	)
	fun failStaleInProgressJobs(
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
