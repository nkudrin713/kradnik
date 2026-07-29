package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
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
    private val downloadAnalytics: DownloadAnalytics,
    private val retryPolicy: DownloadRetryPolicy,
) {
    fun markDownloading(attempt: ClaimedDownloadJob) {
        val job = attempt.job
        statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING)
        downloadAnalytics.recordDownloadStarted(job)
    }

    fun markUploading(attempt: ClaimedDownloadJob) {
        val job = attempt.job
        downloadJobService.markUploading(attempt)
        statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING)
        downloadAnalytics.recordUploadStarted(job)
    }

    fun rejectTooLarge(
        attempt: ClaimedDownloadJob,
        reason: String,
    ) {
        val job = attempt.job
        downloadJobService.markFailed(attempt, reason)
        statusReporter.setStatus(job, TelegramDownloadStatus.REJECTED_TOO_LARGE)
        downloadAnalytics.recordDownloadRejected(job, reason)
    }

    fun failOrRetry(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
        retryAfter: Duration? = null,
    ): DownloadFailureResolution {
        val job = attempt.job
        val retryAt = retryPolicy.retryAt(job.attempts, retryAfter)
        val resolution = downloadJobService.retryAt(attempt, errorMessage, retryAt)
        val status = when (resolution) {
            is DownloadFailureResolution.RetryScheduled -> TelegramDownloadStatus.QUEUED
            is DownloadFailureResolution.TerminalFailure -> TelegramDownloadStatus.ERROR
        }
        statusReporter.setStatus(job, status)
        downloadAnalytics.recordRetryableFailure(job, errorMessage, resolution)
        return resolution
    }

    fun deferBeforeAttempt(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        reason: String,
    ) {
        val job = attempt.job
        downloadJobService.deferBeforeAttempt(attempt, retryAt, reason)
        statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED)
    }

    fun retryAt(
        attempt: ClaimedDownloadJob,
        retryAt: Instant,
        errorMessage: String,
    ): DownloadFailureResolution {
        val job = attempt.job
        val resolution = downloadJobService.retryAt(attempt, errorMessage, retryAt)
        val status = when (resolution) {
            is DownloadFailureResolution.RetryScheduled -> TelegramDownloadStatus.QUEUED
            is DownloadFailureResolution.TerminalFailure -> TelegramDownloadStatus.ERROR
        }
        statusReporter.setStatus(job, status)
        downloadAnalytics.recordRetryableFailure(job, errorMessage, resolution)
        return resolution
    }

    fun failTerminal(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
    ) {
        val job = attempt.job
        downloadJobService.markFailed(attempt, errorMessage)
        statusReporter.setStatus(job, TelegramDownloadStatus.ERROR)
        downloadAnalytics.recordTerminalFailure(job, errorMessage)
    }

    fun failSourceUnavailable(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
    ) {
        val job = attempt.job
        downloadJobService.markFailed(attempt, errorMessage)
        statusReporter.setStatus(job, TelegramDownloadStatus.SOURCE_UNAVAILABLE)
        downloadAnalytics.recordTerminalFailure(job, errorMessage)
    }

    fun failAuthenticationRequired(
        attempt: ClaimedDownloadJob,
        errorMessage: String,
    ) {
        val job = attempt.job
        downloadJobService.markFailed(attempt, errorMessage)
        statusReporter.setStatus(job, TelegramDownloadStatus.AUTHENTICATION_REQUIRED)
        downloadAnalytics.recordAuthenticationRequiredFailure(job, errorMessage)
    }

    fun complete(
        attempt: ClaimedDownloadJob,
        result: DownloadedFileResult,
    ) {
        val job = attempt.job
        downloadJobService.markCompleted(attempt, result)
        statusReporter.deleteStatus(job)
        downloadAnalytics.recordDownloadCompleted(job, result)
    }
}
