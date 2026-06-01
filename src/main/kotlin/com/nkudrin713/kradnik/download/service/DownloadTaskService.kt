package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.domain.DownloadTaskStatus
import com.nkudrin713.kradnik.download.repository.DownloadTaskRepository
import com.nkudrin713.kradnik.download.worker.DownloadQueueNotifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DownloadTaskService(
	private val downloadTaskRepository: DownloadTaskRepository,
	private val downloadQueueNotifier: DownloadQueueNotifier,
) {
	@Transactional
	fun createTask(command: CreateDownloadTaskCommand): DownloadTask {
		val task = downloadTaskRepository.save(
			DownloadTask(
				telegramUserId = command.telegramUserId,
				telegramChatId = command.telegramChatId,
				originalUrl = command.originalUrl,
				normalizedUrl = command.normalizedUrl,
				outputType = command.outputType,
			)
		)
		downloadQueueNotifier.notifyAfterCommit()
		return task
	}

	@Transactional
	fun claimNextQueuedTask(): DownloadTask? =
		downloadTaskRepository.claimNextQueuedTask()

	@Transactional(readOnly = true)
	fun findCachedTask(normalizedUrl: String, outputType: DownloadOutputType): DownloadTask? =
		downloadTaskRepository
			.findFirstByNormalizedUrlAndOutputTypeAndStatusAndTelegramFileIdIsNotNullOrderByCompletedAtDesc(
				normalizedUrl = normalizedUrl,
				outputType = outputType,
				status = DownloadTaskStatus.COMPLETED,
			)

	@Transactional
	fun markCompleted(taskId: Long, result: TelegramFileResult): DownloadTask {
		val task = getTaskInternal(taskId)
		task.status = DownloadTaskStatus.COMPLETED
		task.telegramFileId = result.fileId
		task.telegramFileSize = result.fileSize
		task.errorMessage = null
		task.completedAt = Instant.now()
		return task
	}

	@Transactional
	fun markFailed(taskId: Long, errorMessage: String): DownloadTask {
		val task = getTaskInternal(taskId)
		task.status = DownloadTaskStatus.FAILED
		task.errorMessage = errorMessage
		task.completedAt = Instant.now()
		return task
	}

	@Transactional(readOnly = true)
	fun getTask(taskId: Long): DownloadTask =
		getTaskInternal(taskId)

	private fun getTaskInternal(taskId: Long): DownloadTask =
		downloadTaskRepository.findById(taskId)
			.orElseThrow { DownloadTaskNotFoundException(taskId) }
}

data class CreateDownloadTaskCommand(
	val telegramUserId: Long,
	val telegramChatId: Long,
	val originalUrl: String,
	val normalizedUrl: String,
	val outputType: DownloadOutputType,
)

data class TelegramFileResult(
	val fileId: String,
	val fileSize: Long? = null,
)

class DownloadTaskNotFoundException(taskId: Long) :
	RuntimeException("Download task not found: $taskId")
