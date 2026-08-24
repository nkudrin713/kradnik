package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.MediaMetadata
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

		val spec = command.spec
		val job = downloadJobRepository.save(
			DownloadJob(
				telegramUserId = command.telegramUserId,
				telegramChatId = command.telegramChatId,
				telegramUpdateId = command.telegramUpdateId,
				telegramRequestMessageId = command.telegramRequestMessageId,
				originalUrl = spec.originalUrl,
				normalizedUrl = spec.normalizedUrl,
				cacheKey = spec.cacheKey,
				outputType = spec.outputType,
				downloadStrategy = spec.strategy,
				downloadPreset = spec.presetName,
				selectedFormat = spec.formatSelector,
				downloadExtraArgs = spec.extraArgs,
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
		val jobId = attempt.requiredId()
		val sourceDurationSeconds = metadata.durationSeconds?.toInt()
		val updatedJob = requireOwned(
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

		logger.info(
			"CHAT[{}] JOB[{}] metadata ok: source={}",
			updatedJob.telegramChatId,
			jobId,
			metadata.extractor,
		)

		return updatedJob
	}

	@Transactional
	fun markUploading(attempt: ClaimedDownloadJob): DownloadJob {
		return requireOwned(
			downloadJobRepository.markOwnedUploading(
				jobId = attempt.requiredId(),
				leaseToken = attempt.leaseToken,
			),
			attempt,
		)
	}

	@Transactional
	fun markCompleted(
		attempt: ClaimedDownloadJob,
		result: DownloadedFileResult,
	): DownloadJob {
		val jobId = attempt.requiredId()
		val updatedJob = requireOwned(
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

		logger.info(
			"CHAT[{}] JOB[{}] done: telegramFileSize={}",
			updatedJob.telegramChatId,
			jobId,
			result.telegramFileSize,
		)

		return updatedJob
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
		val jobId = attempt.requiredId()
		val storedReason = reason.take(MAX_ERROR_LENGTH)
		val updatedJob = requireOwned(
			downloadJobRepository.deferOwnedJob(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				reason = storedReason,
				nextAttemptAt = retryAt,
			),
			attempt,
		)

		logger.info(
			"CHAT[{}] JOB[{}] deferred before request: retryAt={}",
			updatedJob.telegramChatId,
			jobId,
			retryAt,
		)

		return updatedJob
	}

	private fun resolveFailure(
		attempt: ClaimedDownloadJob,
		errorMessage: String,
		retryAt: Instant,
	): DownloadFailureResolution {
		val job = attempt.job
		val storedError = errorMessage.take(MAX_ERROR_LENGTH)
		val updatedJob = if (job.attempts >= MAX_ATTEMPTS) {
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
		val resolvedJob = requireOwned(updatedJob, attempt)

		logger.warn(
			"CHAT[{}] JOB[{}] failed: status={}, attempts={}, error={}",
			resolvedJob.telegramChatId,
			requireNotNull(resolvedJob.id),
			resolvedJob.status,
			resolvedJob.attempts,
			resolvedJob.errorMessage,
		)

		return if (resolvedJob.status == DownloadJobStatus.QUEUED) {
			DownloadFailureResolution.RetryScheduled(resolvedJob)
		} else {
			DownloadFailureResolution.TerminalFailure(resolvedJob)
		}
	}

	@Transactional
	fun markFailed(
		attempt: ClaimedDownloadJob,
		errorMessage: String,
	): DownloadJob {
		val jobId = attempt.requiredId()
		val storedError = errorMessage.take(MAX_ERROR_LENGTH)
		val updatedJob = requireOwned(
			downloadJobRepository.failOwnedJob(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				errorMessage = storedError,
			),
			attempt,
		)

		logger.warn(
			"CHAT[{}] JOB[{}] failed: status={}, attempts={}, error={}",
			updatedJob.telegramChatId,
			jobId,
			updatedJob.status,
			updatedJob.attempts,
			updatedJob.errorMessage,
		)

		return updatedJob
	}

	private fun requireOwned(updatedJob: DownloadJob?, attempt: ClaimedDownloadJob): DownloadJob {
		return updatedJob ?: throw DownloadJobLeaseLostException(attempt.requiredId())
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
	val spec: DownloadSpec,
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
