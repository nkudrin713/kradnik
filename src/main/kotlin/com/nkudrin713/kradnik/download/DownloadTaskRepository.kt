package com.nkudrin713.kradnik.download

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface DownloadTaskRepository : JpaRepository<DownloadTask, Long> {
	fun findFirstByNormalizedUrlAndOutputTypeAndStatusAndTelegramFileIdIsNotNullOrderByCompletedAtDesc(
		normalizedUrl: String,
		outputType: DownloadOutputType,
		status: DownloadTaskStatus,
	): DownloadTask?

	fun findByStatusOrderByCreatedAtAsc(
		status: DownloadTaskStatus,
		pageable: Pageable,
	): List<DownloadTask>
}
