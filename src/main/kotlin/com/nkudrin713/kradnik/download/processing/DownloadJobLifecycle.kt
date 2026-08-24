package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
import com.nkudrin713.kradnik.download.service.DownloadFailureResolution
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class DownloadJobLifecycle(
    private val downloadJobService: DownloadJobService,
    private val statusReporter: DownloadStatusReporter,
    private val retryPolicy: DownloadRetryPolicy,
) {
    fun markDownloading(attempt: ClaimedDownloadJob) {
        val job = attempt.job
        statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING)
    }

    fun markUploading(attempt: ClaimedDownloadJob) {
        val job = downloadJobService.markUploading(attempt)
        statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING)
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
    ): DownloadFailureResolution {
        val retryAt = retryPolicy.retryAt(attempt.job.attempts, retryAfter)
        return retry(attempt, retryAt, errorMessage)
    }

    fun deferBeforeAttempt(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        reason: String,
    ) {
        val job = downloadJobService.deferBeforeAttempt(attempt, retryAt, reason)
        statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED)
    }

    fun retryAt(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        errorMessage: String,
    ): DownloadFailureResolution {
        return retry(attempt, retryAt, errorMessage)
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
        result: DownloadedFileResult,
    ) {
        val job = downloadJobService.markCompleted(attempt, result)
        statusReporter.deleteStatus(job)
    }

    private fun retry(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        errorMessage: String,
    ): DownloadFailureResolution {
        val resolution = downloadJobService.retryAt(attempt, errorMessage, retryAt)
        val status = when (resolution) {
            is DownloadFailureResolution.RetryScheduled -> TelegramDownloadStatus.QUEUED
            is DownloadFailureResolution.TerminalFailure -> TelegramDownloadStatus.ERROR
        }
        statusReporter.setStatus(resolution.job, status)
        return resolution
    }

    private fun fail(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
        status: TelegramDownloadStatus,
    ) {
        val job = downloadJobService.markFailed(attempt, errorMessage)
        statusReporter.setStatus(job, status)
    }
}
