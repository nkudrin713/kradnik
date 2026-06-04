package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface DownloadJobRepository : JpaRepository<DownloadJob, Long> {

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
                updated_at = now()
            FROM picked
            WHERE download_jobs.id = picked.id
            RETURNING download_jobs.*
        """,
		nativeQuery = true,
	)
	fun claimNextQueuedJob(maxAttempts: Int): DownloadJob?

	@Modifying
	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'queued',
			    error_message = 'Recovered stale in-progress job',
			    updated_at = now()
			WHERE status IN ('processing', 'uploading')
			  AND updated_at < :staleBefore
			  AND attempts < :maxAttempts
		""",
		nativeQuery = true,
	)
	fun requeueStaleInProgressJobs(
		staleBefore: Instant,
		maxAttempts: Int,
	): Int

	@Modifying
	@Query(
		value = """
			UPDATE download_jobs
			SET status = 'failed',
			    error_message = 'Failed after stale in-progress recovery',
			    completed_at = now(),
			    updated_at = now()
			WHERE status IN ('processing', 'uploading')
			  AND updated_at < :staleBefore
			  AND attempts >= :maxAttempts
		""",
		nativeQuery = true,
	)
	fun failStaleInProgressJobs(
		staleBefore: Instant,
		maxAttempts: Int,
	): Int

	@Query(
		value = """
			SELECT *
			FROM download_jobs
			WHERE normalized_url = :normalizedUrl
			  AND output_type = :#{#outputType.dbValue}
			  AND status = 'completed'
			  AND telegram_file_id IS NOT NULL
			ORDER BY completed_at DESC
			LIMIT 1
		""",
		nativeQuery = true,
	)
	fun findCachedCompletedJob(
		normalizedUrl: String,
		outputType: OutputType,
	): DownloadJob?
}
