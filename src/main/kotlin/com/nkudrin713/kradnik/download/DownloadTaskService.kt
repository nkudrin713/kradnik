package com.nkudrin713.kradnik.download

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DownloadTaskService(
	private val downloadTaskRepository: DownloadTaskRepository,
) {
	@Transactional
	fun createTask(command: CreateDownloadTaskCommand): DownloadTask =
		downloadTaskRepository.save(
			DownloadTask(
				telegramUserId = command.telegramUserId,
				telegramChatId = command.telegramChatId,
				originalUrl = command.originalUrl,
				normalizedUrl = command.normalizedUrl,
				outputType = command.outputType,
			)
		)

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
		val task = getTask(taskId)
		task.status = DownloadTaskStatus.COMPLETED
		task.telegramFileId = result.fileId
		task.telegramFileSize = result.fileSize
		task.errorMessage = null
		task.completedAt = Instant.now()
		return task
	}

	@Transactional
	fun markFailed(taskId: Long, errorMessage: String): DownloadTask {
		val task = getTask(taskId)
		task.status = DownloadTaskStatus.FAILED
		task.errorMessage = errorMessage
		task.completedAt = Instant.now()
		return task
	}

	private fun getTask(taskId: Long): DownloadTask =
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
