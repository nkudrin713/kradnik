package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DownloadJobService(
	private val downloadJobRepository: DownloadJobRepository,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun createJob(command: CreateDownloadJobCommand): CreateDownloadJobResult {
		command.telegramUpdateId?.let { telegramUpdateId ->
			downloadJobRepository.lockTelegramUpdate(telegramUpdateId)
			downloadJobRepository.findByTelegramUpdateId(telegramUpdateId)?.let {
				return CreateDownloadJobResult.Existing(it)
			}
		}

		val job = downloadJobRepository.save(
			DownloadJob(
				telegramUserId = command.telegramUserId,
				telegramChatId = command.telegramChatId,
				telegramUpdateId = command.telegramUpdateId,
				telegramRequestMessageId = command.telegramRequestMessageId,
				originalUrl = command.originalUrl,
				normalizedUrl = command.normalizedUrl,
				cacheKey = command.cacheKey,
				outputType = command.outputType,
				downloadStrategy = command.downloadStrategy,
				downloadPreset = command.downloadPreset,
				selectedFormat = command.selectedFormat,
				downloadExtraArgs = command.downloadExtraArgs,
				telegramStatusMessageId = command.telegramStatusMessageId,
			)
		)
		return CreateDownloadJobResult.Created(job)
	}

	@Transactional
	fun claimNextQueuedJob(
		leaseToken: UUID,
		leaseDurationMs: Long,
	): ClaimedDownloadJob? {
		val job = downloadJobRepository.claimNextQueuedJob(
			maxAttempts = MAX_ATTEMPTS,
			leaseToken = leaseToken,
			leaseDurationMs = leaseDurationMs,
		) ?: return null
		return ClaimedDownloadJob(job, leaseToken)
	}

	@Transactional
	fun renewLease(
		jobId: Long,
		leaseToken: UUID,
		leaseDurationMs: Long,
	): Boolean {
		return downloadJobRepository.renewLease(jobId, leaseToken, leaseDurationMs) == 1
	}

	@Transactional
	fun recoverExpiredLeases(): DownloadJobRecoveryResult {
		val requeued = downloadJobRepository.requeueStaleInProgressJobs(
			maxAttempts = MAX_ATTEMPTS,
		)
		val failed = downloadJobRepository.failStaleInProgressJobs(
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

	@Transactional(readOnly = true)
	fun ownsLease(jobId: Long, leaseToken: UUID): Boolean {
		return downloadJobRepository.existsByIdAndLeaseToken(jobId, leaseToken)
	}

	@Transactional
	fun markMetadata(attempt: ClaimedDownloadJob, metadata: MediaMetadata): DownloadJob {
		val job = attempt.job
		val jobId = attempt.requiredId()
		val sourceDurationSeconds = metadata.durationSeconds?.toInt()
		ensureOwned(
			downloadJobRepository.updateOwnedMetadata(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				sourceTitle = metadata.title,
				sourceExtractor = metadata.extractor,
				sourceDurationSeconds = sourceDurationSeconds,
				sourceAudioTitle = metadata.audioTitle,
				sourceAudioPerformer = metadata.audioPerformer,
			),
			attempt,
		)

		job.sourceTitle = metadata.title
		job.sourceExtractor = metadata.extractor
		job.sourceDurationSeconds = sourceDurationSeconds
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
	fun markUploading(attempt: ClaimedDownloadJob): DownloadJob {
		val job = attempt.job
		ensureOwned(
			downloadJobRepository.markOwnedUploading(
				jobId = attempt.requiredId(),
				leaseToken = attempt.leaseToken,
			),
			attempt,
		)

		job.status = DownloadJobStatus.UPLOADING
		job.uploadingStartedAt = Instant.now()

		return job
	}

	@Transactional
	fun markCompleted(
		attempt: ClaimedDownloadJob,
		result: DownloadedFileResult,
	): DownloadJob {
		val job = attempt.job
		val jobId = attempt.requiredId()
		ensureOwned(
			downloadJobRepository.markOwnedCompleted(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				telegramFileId = result.telegramFileId,
				telegramFileSize = result.telegramFileSize,
				downloadedFileSize = result.downloadedFileSize,
				downloadedAt = result.downloadedAt,
			),
			attempt,
		)

		job.status = DownloadJobStatus.COMPLETED

		job.downloadedFileSize = result.downloadedFileSize

		job.telegramFileId = result.telegramFileId
		job.telegramFileSize = result.telegramFileSize

		job.errorMessage = null
		job.leaseToken = null
		job.leaseExpiresAt = null
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
	fun retryAt(
		attempt: ClaimedDownloadJob,
		errorMessage: String,
		retryAt: Instant,
	): DownloadFailureResolution {
		return resolveFailure(attempt, errorMessage, retryAt)
	}

	@Transactional
	fun deferBeforeAttempt(
		attempt: ClaimedDownloadJob,
		retryAt: Instant,
		reason: String,
	): DownloadJob {
		val job = attempt.job
		val jobId = attempt.requiredId()
		val storedReason = reason.take(MAX_ERROR_LENGTH)
		ensureOwned(
			downloadJobRepository.deferOwnedJob(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				reason = storedReason,
				nextAttemptAt = retryAt,
			),
			attempt,
		)
		job.status = DownloadJobStatus.QUEUED
		job.attempts = (job.attempts - 1).coerceAtLeast(0)
		job.nextAttemptAt = retryAt
		job.errorMessage = storedReason
		job.leaseToken = null
		job.leaseExpiresAt = null

		logger.info(
			"CHAT[{}] JOB[{}] deferred before request: retryAt={}",
			job.telegramChatId,
			jobId,
			retryAt,
		)

		return job
	}

	private fun resolveFailure(
		attempt: ClaimedDownloadJob,
		errorMessage: String,
		retryAt: Instant,
	): DownloadFailureResolution {
		val job = attempt.job
		val storedError = errorMessage.take(MAX_ERROR_LENGTH)
		val updatedRows = if (job.attempts >= MAX_ATTEMPTS) {
			downloadJobRepository.failOwnedJob(
				jobId = attempt.requiredId(),
				leaseToken = attempt.leaseToken,
				errorMessage = storedError,
			)
		} else {
			downloadJobRepository.requeueOwnedJob(
				jobId = attempt.requiredId(),
				leaseToken = attempt.leaseToken,
				errorMessage = storedError,
				nextAttemptAt = retryAt,
			)
		}
		ensureOwned(updatedRows, attempt)

		job.errorMessage = storedError
		job.leaseToken = null
		job.leaseExpiresAt = null

		if (job.attempts >= MAX_ATTEMPTS) {
			job.status = DownloadJobStatus.FAILED
			job.completedAt = Instant.now()
		} else {
			job.status = DownloadJobStatus.QUEUED
			job.nextAttemptAt = retryAt
		}

		logger.warn(
			"CHAT[{}] JOB[{}] failed: status={}, attempts={}, error={}",
			job.telegramChatId,
			requireNotNull(job.id),
			job.status,
			job.attempts,
			job.errorMessage,
		)

		return if (job.status == DownloadJobStatus.QUEUED) {
			DownloadFailureResolution.RetryScheduled(job)
		} else {
			DownloadFailureResolution.TerminalFailure(job)
		}
	}

	@Transactional
	fun markFailed(
		attempt: ClaimedDownloadJob,
		errorMessage: String,
	): DownloadJob {
		val job = attempt.job
		val jobId = attempt.requiredId()
		val storedError = errorMessage.take(MAX_ERROR_LENGTH)
		ensureOwned(
			downloadJobRepository.failOwnedJob(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				errorMessage = storedError,
			),
			attempt,
		)

		job.status = DownloadJobStatus.FAILED
		job.errorMessage = storedError
		job.completedAt = Instant.now()
		job.leaseToken = null
		job.leaseExpiresAt = null

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

	private fun ensureOwned(updatedRows: Int, attempt: ClaimedDownloadJob) {
		if (updatedRows != 1) {
			throw DownloadJobLeaseLostException(attempt.requiredId())
		}
	}

	private companion object {
		private const val MAX_ATTEMPTS = 3
		private const val MAX_ERROR_LENGTH = 1000
	}
}

class DownloadJobLeaseLostException(jobId: Long) :
	CancellationException("Download job lease lost: $jobId")

data class ClaimedDownloadJob(
	val job: DownloadJob,
	val leaseToken: UUID,
) {
	fun requiredId(): Long = requireNotNull(job.id)
}

data class CreateDownloadJobCommand(
	val telegramUserId: Long,
	val telegramChatId: Long,
	val telegramUpdateId: Int? = null,
	val telegramRequestMessageId: Int? = null,
	val originalUrl: String,
	val normalizedUrl: String,
	val cacheKey: String,
	val outputType: OutputType,
	val downloadStrategy: DownloadStrategy,
	val downloadPreset: String,
	val selectedFormat: String,
	val downloadExtraArgs: List<String> = emptyList(),
	val telegramStatusMessageId: Int? = null,
)

sealed interface CreateDownloadJobResult {
	val job: DownloadJob

	data class Created(
		override val job: DownloadJob,
	) : CreateDownloadJobResult

	data class Existing(
		override val job: DownloadJob,
	) : CreateDownloadJobResult
}

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

sealed interface DownloadFailureResolution {
	val job: DownloadJob

	data class RetryScheduled(
		override val job: DownloadJob,
	) : DownloadFailureResolution

	data class TerminalFailure(
		override val job: DownloadJob,
	) : DownloadFailureResolution
}
