package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DownloadJobService(
	private val downloadJobRepository: DownloadJobRepository,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun createJob(command: CreateDownloadJobCommand): DownloadJob {
		return downloadJobRepository.save(
			DownloadJob(
				telegramUserId = command.telegramUserId,
				telegramChatId = command.telegramChatId,
				originalUrl = command.originalUrl,
				normalizedUrl = command.normalizedUrl,
				cacheKey = command.cacheKey,
				outputType = command.outputType,
				downloadPreset = command.downloadPreset,
				selectedFormat = command.selectedFormat,
				downloadExtraArgs = command.downloadExtraArgs,
				telegramStatusMessageId = command.telegramStatusMessageId,
			)
		)
	}

	@Transactional
	fun claimNextQueuedJob(): DownloadJob? {
		return downloadJobRepository.claimNextQueuedJob(MAX_ATTEMPTS)
	}

	@Transactional
	fun recoverStaleInProgressJobs(staleBefore: Instant): DownloadJobRecoveryResult {
		val requeued = downloadJobRepository.requeueStaleInProgressJobs(
			staleBefore = staleBefore,
			maxAttempts = MAX_ATTEMPTS,
		)
		val failed = downloadJobRepository.failStaleInProgressJobs(
			staleBefore = staleBefore,
			maxAttempts = MAX_ATTEMPTS,
		)

		if (requeued > 0 || failed > 0) {
			logger.warn(
				"Recovered stale download jobs: requeued={}, failed={}",
				requeued,
				failed,
			)
		}

		return DownloadJobRecoveryResult(
			requeued = requeued,
			failed = failed,
		)
	}

	@Transactional(readOnly = true)
	fun findCachedJob(job: DownloadJob): DownloadJob? {
		return downloadJobRepository
			.findCachedCompletedJob(
				cacheKey = job.cacheKey,
			)
	}

	@Transactional
	fun markMetadata(jobId: Long, metadata: MediaMetadata): DownloadJob {
		val job = getJobInternal(jobId)

		job.sourceTitle = metadata.title
		job.sourceExtractor = metadata.extractor
		job.sourceDurationSeconds = metadata.durationSeconds?.toInt()
		job.sourceAudioTitle = metadata.audioTitle
		job.sourceAudioPerformer = metadata.audioPerformer

		logger.info(
			"CHAT[{}] JOB[{}] metadata ok: source={}",
			job.telegramChatId,
			jobId,
			metadata.extractor,
		)

		return job
	}

	@Transactional
	fun markUploading(jobId: Long): DownloadJob {
		val job = getJobInternal(jobId)

		job.status = DownloadJobStatus.UPLOADING
		job.uploadingStartedAt = Instant.now()

		return job
	}

	@Transactional
	fun markCompleted(
		jobId: Long,
		result: DownloadedFileResult,
	): DownloadJob {
		val job = getJobInternal(jobId)

		job.status = DownloadJobStatus.COMPLETED

		job.downloadedFileSize = result.downloadedFileSize

		job.telegramFileId = result.telegramFileId
		job.telegramFileSize = result.telegramFileSize

		job.errorMessage = null
		job.downloadedAt = result.downloadedAt ?: Instant.now()
		job.completedAt = Instant.now()

		logger.info(
			"CHAT[{}] JOB[{}] done: telegramFileSize={}",
			job.telegramChatId,
			jobId,
			result.telegramFileSize,
		)

		return job
	}

	@Transactional
	fun markFailedOrRetry(
		jobId: Long,
		errorMessage: String,
	): DownloadJob {
		val job = getJobInternal(jobId)

		job.errorMessage = errorMessage.take(1000)

		if (job.attempts >= MAX_ATTEMPTS) {
			job.status = DownloadJobStatus.FAILED
			job.completedAt = Instant.now()
		} else {
			job.status = DownloadJobStatus.QUEUED
		}

		logger.warn(
			"CHAT[{}] JOB[{}] failed: status={}, attempts={}, error={}",
			job.telegramChatId,
			jobId,
			job.status,
			job.attempts,
			job.errorMessage,
		)

		return job
	}

	@Transactional
	fun markFailed(
		jobId: Long,
		errorMessage: String,
	): DownloadJob {
		val job = getJobInternal(jobId)

		job.status = DownloadJobStatus.FAILED
		job.errorMessage = errorMessage.take(1000)
		job.completedAt = Instant.now()

		logger.warn(
			"CHAT[{}] JOB[{}] failed: status={}, attempts={}, error={}",
			job.telegramChatId,
			jobId,
			job.status,
			job.attempts,
			job.errorMessage,
		)

		return job
	}

	@Transactional(readOnly = true)
	fun getJob(jobId: Long): DownloadJob {
		return getJobInternal(jobId)
	}

	private fun getJobInternal(jobId: Long): DownloadJob {
		return downloadJobRepository.findById(jobId)
			.orElseThrow { DownloadJobNotFoundException(jobId) }
	}

	private companion object {
		private const val MAX_ATTEMPTS = 3
	}
}

class DownloadJobNotFoundException(jobId: Long) :
	RuntimeException("Download job not found: $jobId")

data class CreateDownloadJobCommand(
	val telegramUserId: Long,
	val telegramChatId: Long,
	val originalUrl: String,
	val normalizedUrl: String,
	val cacheKey: String,
	val outputType: OutputType,
	val downloadPreset: String,
	val selectedFormat: String,
	val downloadExtraArgs: List<String> = emptyList(),
	val telegramStatusMessageId: Int? = null,
)

data class DownloadedFileResult(
	val telegramFileId: String,
	val telegramFileSize: Long? = null,
	val downloadedFileSize: Long? = null,
	val downloadedAt: Instant? = null,
)

data class DownloadJobRecoveryResult(
	val requeued: Int,
	val failed: Int,
)
