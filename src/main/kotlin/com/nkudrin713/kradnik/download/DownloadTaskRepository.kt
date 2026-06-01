package com.nkudrin713.kradnik.download

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DownloadTaskRepository : JpaRepository<DownloadTask, Long> {
	fun findFirstByNormalizedUrlAndOutputTypeAndStatusAndTelegramFileIdIsNotNullOrderByCompletedAtDesc(
		normalizedUrl: String,
		outputType: DownloadOutputType,
		status: DownloadTaskStatus,
	): DownloadTask?

	@Query(
		value = """
			WITH picked AS (
				SELECT id
				FROM download_tasks
				WHERE status = 'queued'
				ORDER BY created_at
				FOR UPDATE SKIP LOCKED
				LIMIT 1
			)
			UPDATE download_tasks
			SET status = 'processing',
				updated_at = now()
			FROM picked
			WHERE download_tasks.id = picked.id
			RETURNING download_tasks.*
		""",
		nativeQuery = true,
	)
	fun claimNextQueuedTask(): DownloadTask?
}
