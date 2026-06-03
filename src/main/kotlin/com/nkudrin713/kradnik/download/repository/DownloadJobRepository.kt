package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
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
}
