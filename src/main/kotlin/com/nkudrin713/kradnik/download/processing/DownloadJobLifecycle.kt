package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.requiredId
import com.nkudrin713.kradnik.download.service.DownloadFailureResolution
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import org.springframework.stereotype.Component

@Component
class DownloadJobLifecycle(
    private val downloadJobService: DownloadJobService,
    private val statusReporter: DownloadStatusReporter,
) {
    fun markDownloading(job: DownloadJob) {
        statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING)
    }

    fun markUploading(job: DownloadJob) {
        downloadJobService.markUploading(job.requiredId())
        statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING)
    }

    fun rejectTooLarge(
        job: DownloadJob,
        reason: String,
    ) {
        downloadJobService.markFailed(job.requiredId(), reason)
        statusReporter.setStatus(job, TelegramDownloadStatus.REJECTED_TOO_LARGE)
    }

    fun failOrRetry(
        job: DownloadJob,
        errorMessage: String,
    ): DownloadFailureResolution {
        val resolution = downloadJobService.markFailedOrRetry(job.requiredId(), errorMessage)
        val status = when (resolution) {
            is DownloadFailureResolution.RetryScheduled -> TelegramDownloadStatus.QUEUED
            is DownloadFailureResolution.TerminalFailure -> TelegramDownloadStatus.ERROR
        }
        statusReporter.setStatus(job, status)
        return resolution
    }

    fun failAuthenticationRequired(
        job: DownloadJob,
        errorMessage: String,
    ) {
        downloadJobService.markFailed(job.requiredId(), errorMessage)
        statusReporter.setStatus(job, TelegramDownloadStatus.AUTHENTICATION_REQUIRED)
    }

    fun complete(
        job: DownloadJob,
        result: DownloadedFileResult,
    ) {
        downloadJobService.markCompleted(job.requiredId(), result)
        statusReporter.setStatus(job, TelegramDownloadStatus.COMPLETED)
    }
}
