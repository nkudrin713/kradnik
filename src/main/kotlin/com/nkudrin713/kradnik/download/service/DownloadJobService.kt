package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Persists queue transitions and requires lease ownership for mutations of claimed jobs. */
@Service
class DownloadJobService(
	private val downloadJobRepository: DownloadJobRepository,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	/** Creates at most one job for a Telegram update when an update ID is present. */
	@Transactional
	fun createJob(command: CreateDownloadJobCommand): Boolean {
		command.telegramUpdateId?.let { telegramUpdateId ->
			downloadJobRepository.lockTelegramUpdate(telegramUpdateId)
			if (downloadJobRepository.findByTelegramUpdateId(telegramUpdateId) != null) {
				return false
			}
		}

		val spec = command.spec
		downloadJobRepository.save(
			DownloadJob(
				telegramUserId = command.telegramUserId,
				telegramChatId = command.telegramChatId,
				telegramUpdateId = command.telegramUpdateId,
				telegramRequestMessageId = command.telegramRequestMessageId,
				originalUrl = spec.originalUrl,
				normalizedUrl = spec.normalizedUrl,
				cacheKey = spec.cacheKey,
				outputType = spec.outputType,
				platform = spec.platform,
				downloadPreset = spec.presetName,
				selectedFormat = spec.formatSelector,
				downloadExtraArgs = spec.extraArgs,
				telegramStatusMessageId = command.telegramStatusMessageId,
			)
		)
		return true
	}

	/** Atomically claims the oldest eligible job and associates it with [leaseToken]. */
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

	/** Recovers expired leases by requeueing retryable jobs and failing exhausted ones. */
	@Transactional
	fun recoverExpiredLeases() {
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
	fun markAudioMetadata(
		attempt: ClaimedDownloadJob,
		durationSeconds: Int?,
		title: String,
		performer: String,
	): DownloadJob {
		val jobId = attempt.requiredId()
		return requireOwned(
			downloadJobRepository.updateOwnedMetadata(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				sourceDurationSeconds = durationSeconds,
				sourceAudioTitle = title,
				sourceAudioPerformer = performer,
			),
			attempt,
		)
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
		telegramFileId: String,
	): DownloadJob {
		val jobId = attempt.requiredId()
		val updatedJob = requireOwned(
			downloadJobRepository.markOwnedCompleted(
				jobId = jobId,
				leaseToken = attempt.leaseToken,
				telegramFileId = telegramFileId,
			),
			attempt,
		)

		logger.info("CHAT[{}] JOB[{}] done", updatedJob.telegramChatId, jobId)

		return updatedJob
	}

	@Transactional
	fun retryAt(
		attempt: ClaimedDownloadJob,
		errorMessage: String,
		retryAt: Instant,
	): DownloadJob {
		return resolveFailure(attempt, errorMessage, retryAt)
	}

	/** Requeues without consuming an attempt because no source request was made. */
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
	): DownloadJob {
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

		return resolvedJob
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

/** Cancels the current attempt when its database lease no longer belongs to this worker. */
class DownloadJobLeaseLostException(jobId: Long) :
	CancellationException("Download job lease lost: $jobId")

/** A job snapshot paired with the token required to mutate its current lease. */
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
