package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.domain.DownloadTaskStatus
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.repository.DownloadTaskRepository
import com.nkudrin713.kradnik.download.worker.DownloadQueueNotifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DownloadTaskService(
	private val downloadTaskRepository: DownloadTaskRepository,
	private val downloadQueueNotifier: DownloadQueueNotifier,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

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

	@Transactional
	fun markMetadata(taskId: Long, metadata: MediaMetadata): DownloadTask {
		val task = getTaskInternal(taskId)
		task.sourceTitle = metadata.title
		task.sourceExtractor = metadata.extractor
		task.sourceDurationSeconds = metadata.durationSeconds?.toInt()
		logger.info("CHAT[{}] TASK[{}] metadata ok: source={}", task.telegramChatId, taskId, metadata.extractor)
		return task
	}

	@Transactional
	fun markUploading(taskId: Long): DownloadTask {
		val task = getTaskInternal(taskId)
		task.status = DownloadTaskStatus.UPLOADING
		return task
	}

	@Transactional
	fun markCompleted(taskId: Long, result: TelegramFileResult): DownloadTask {
		val task = getTaskInternal(taskId)
		task.status = DownloadTaskStatus.COMPLETED
		task.telegramFileId = result.fileId
		task.telegramFileSize = result.fileSize
		task.errorMessage = null
		task.completedAt = Instant.now()
		logger.info("CHAT[{}] TASK[{}] done: telegramFileSize={}", task.telegramChatId, taskId, result.fileSize)
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

	@Transactional
	fun setStatusMessageId(taskId: Long, messageId: Int): DownloadTask {
		val task = getTaskInternal(taskId)
		task.telegramStatusMessageId = messageId
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
