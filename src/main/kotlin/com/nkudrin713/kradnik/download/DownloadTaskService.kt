package com.nkudrin713.kradnik.download

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
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

	@Transactional(readOnly = true)
	fun findCachedTask(normalizedUrl: String, outputType: DownloadOutputType): DownloadTask? =
		downloadTaskRepository
			.findFirstByNormalizedUrlAndOutputTypeAndStatusAndTelegramFileIdIsNotNullOrderByCompletedAtDesc(
				normalizedUrl = normalizedUrl,
				outputType = outputType,
				status = DownloadTaskStatus.COMPLETED,
			)

	@Transactional(readOnly = true)
	fun findQueuedTasks(limit: Int): List<DownloadTask> =
		downloadTaskRepository.findByStatusOrderByCreatedAtAsc(
			status = DownloadTaskStatus.QUEUED,
			pageable = PageRequest.of(0, limit),
		)

	@Transactional
	fun markProcessing(taskId: Long, sourceMetadata: SourceMetadata? = null): DownloadTask {
		val task = getTask(taskId)
		task.status = DownloadTaskStatus.PROCESSING
		task.errorMessage = null
		sourceMetadata?.applyTo(task)
		return task
	}

	@Transactional
	fun markUploading(taskId: Long, outputMetadata: OutputMetadata? = null): DownloadTask {
		val task = getTask(taskId)
		task.status = DownloadTaskStatus.UPLOADING
		outputMetadata?.applyTo(task)
		return task
	}

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

data class SourceMetadata(
	val title: String? = null,
	val extractor: String? = null,
	val durationSeconds: Int? = null,
	val formatId: String? = null,
	val ext: String? = null,
	val width: Int? = null,
	val height: Int? = null,
	val fps: BigDecimal? = null,
	val filesize: Long? = null,
) {
	fun applyTo(task: DownloadTask) {
		task.sourceTitle = title
		task.sourceExtractor = extractor
		task.sourceDurationSeconds = durationSeconds
		task.sourceFormatId = formatId
		task.sourceExt = ext
		task.sourceWidth = width
		task.sourceHeight = height
		task.sourceFps = fps
		task.sourceFilesize = filesize
	}
}

data class OutputMetadata(
	val ext: String? = null,
	val audioCodec: String? = null,
	val videoCodec: String? = null,
	val bitrate: String? = null,
	val args: Map<String, Any?>? = null,
) {
	fun applyTo(task: DownloadTask) {
		task.outputExt = ext
		task.outputAudioCodec = audioCodec
		task.outputVideoCodec = videoCodec
		task.outputBitrate = bitrate
		task.outputArgs = args
	}
}

data class TelegramFileResult(
	val fileId: String,
	val fileSize: Long? = null,
)

class DownloadTaskNotFoundException(taskId: Long) :
	RuntimeException("Download task not found: $taskId")
