package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DownloadJobRepository : JpaRepository<DownloadJob, Long> {

	@Query(
		value = """
            WITH picked AS (
                SELECT id
                FROM download_jobs
                WHERE status = 'queued'
                  AND attempts < 3
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
	fun claimNextQueuedJob(): DownloadJob?

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
