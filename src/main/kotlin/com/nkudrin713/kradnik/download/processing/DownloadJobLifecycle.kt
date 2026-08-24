package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class DownloadJobLifecycle(
    private val downloadJobService: DownloadJobService,
    private val telegramSender: TelegramSender,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun markDownloading(attempt: ClaimedDownloadJob) {
        setStatus(attempt.job, TelegramDownloadStatus.DOWNLOADING)
    }

    fun markUploading(attempt: ClaimedDownloadJob) {
        val job = downloadJobService.markUploading(attempt)
        setStatus(job, TelegramDownloadStatus.UPLOADING)
    }

    fun rejectTooLarge(
        attempt: ClaimedDownloadJob,
        reason: String,
    ) {
        fail(
            attempt = attempt,
            errorMessage = reason,
            status = TelegramDownloadStatus.REJECTED_TOO_LARGE,
        )
    }

    fun failOrRetry(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
        retryAfter: Duration? = null,
    ) {
        retry(attempt, nextRetryAt(attempt.job.attempts, retryAfter), errorMessage)
    }

    fun deferBeforeAttempt(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        reason: String,
    ) {
        val job = downloadJobService.deferBeforeAttempt(attempt, retryAt, reason)
        setStatus(job, TelegramDownloadStatus.QUEUED)
    }

    fun retryAt(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        errorMessage: String,
    ) {
        retry(attempt, retryAt, errorMessage)
    }

    fun failTerminal(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
    ) {
        fail(
            attempt = attempt,
            errorMessage = errorMessage,
            status = TelegramDownloadStatus.ERROR,
        )
    }

    fun failSourceUnavailable(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
    ) {
        fail(
            attempt = attempt,
            errorMessage = errorMessage,
            status = TelegramDownloadStatus.SOURCE_UNAVAILABLE,
        )
    }

    fun failAuthenticationRequired(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
    ) {
        fail(
            attempt = attempt,
            errorMessage = errorMessage,
            status = TelegramDownloadStatus.AUTHENTICATION_REQUIRED,
        )
    }

    fun complete(
        attempt: ClaimedDownloadJob,
        telegramFileId: String,
        downloadedFileSize: Long?,
    ) {
        val job = downloadJobService.markCompleted(
            attempt = attempt,
            telegramFileId = telegramFileId,
            downloadedFileSize = downloadedFileSize,
        )
        deleteStatus(job)
    }

    private fun retry(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        errorMessage: String,
    ) {
        val job = downloadJobService.retryAt(attempt, errorMessage, retryAt)
        val status = when (job.status) {
            DownloadJobStatus.QUEUED -> TelegramDownloadStatus.QUEUED
            else -> TelegramDownloadStatus.ERROR
        }
        setStatus(job, status)
    }

    private fun fail(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
        status: TelegramDownloadStatus,
    ) {
        val job = downloadJobService.markFailed(attempt, errorMessage)
        setStatus(job, status)
    }

    private fun nextRetryAt(attempt: Int, retryAfter: Duration?): Instant {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        val backoff = minOf(
            BASE_RETRY_DELAY.multipliedBy(1L shl exponent),
            MAX_RETRY_DELAY,
        )
        val requestedDelay = retryAfter
            ?.takeUnless(Duration::isNegative)
            ?: Duration.ZERO

        return clock.instant().plus(maxOf(backoff, requestedDelay))
    }

    private fun setStatus(job: DownloadJob, status: TelegramDownloadStatus) {
        runCatching {
            telegramSender.editStatus(
                job.telegramChatId,
                job.telegramStatusMessageId,
                status,
            )
        }.onFailure {
            logger.warn("JOB[{}] status message update failed: {}", job.id, it.message)
        }
    }

    private fun deleteStatus(job: DownloadJob) {
        val messageId = job.telegramStatusMessageId ?: return
        runCatching {
            telegramSender.deleteMessage(job.telegramChatId, messageId)
        }.onFailure {
            logger.warn("JOB[{}] status message deletion failed: {}", job.id, it.message)
        }
    }

    private companion object {
        private val BASE_RETRY_DELAY = Duration.ofSeconds(15)
        private val MAX_RETRY_DELAY = Duration.ofMinutes(10)
        private const val MAX_EXPONENT = 10
    }
}
