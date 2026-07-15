package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.DownloadFailureResolution
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant
import kotlin.test.Test

class DownloadJobLifecycleTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val statusReporter: DownloadStatusReporter = mockk()
    private val downloadAnalytics: DownloadAnalytics = mockk(relaxed = true)
    private val lifecycle = DownloadJobLifecycle(
        downloadJobService = downloadJobService,
        statusReporter = statusReporter,
        downloadAnalytics = downloadAnalytics,
    )

    @Test
    fun marksDownloading() {
        val job = job()
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.markDownloading(job)

        verify { statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING) }
    }

    @Test
    fun marksUploading() {
        val job = job()
        every { downloadJobService.markUploading(1) } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.markUploading(job)

        verify { downloadJobService.markUploading(1) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING) }
    }

    @Test
    fun rejectsTooLarge() {
        val job = job()
        every { downloadJobService.markFailed(1, "too large") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.rejectTooLarge(job, "too large")

        verify { downloadJobService.markFailed(1, "too large") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.REJECTED_TOO_LARGE) }
    }

    @Test
    fun reportsQueuedStatusWhenRetryIsScheduled() {
        val job = job()
        every { downloadJobService.markFailedOrRetry(1, "error") } returns
                DownloadFailureResolution.RetryScheduled(job)
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failOrRetry(job, "error")

        verify { downloadJobService.markFailedOrRetry(1, "error") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED) }
    }

    @Test
    fun reportsErrorStatusWhenAttemptsAreExhausted() {
        val job = job()
        every { downloadJobService.markFailedOrRetry(1, "error") } returns
                DownloadFailureResolution.TerminalFailure(job)
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failOrRetry(job, "error")

        verify { statusReporter.setStatus(job, TelegramDownloadStatus.ERROR) }
    }

    @Test
    fun defersBeforeAttemptAndKeepsQueuedStatus() {
        val job = job()
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { downloadJobService.deferBeforeAttempt(1, retryAt, "rate limited") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.deferBeforeAttempt(job, retryAt, "rate limited")

        verify { downloadJobService.deferBeforeAttempt(1, retryAt, "rate limited") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED) }
    }

    @Test
    fun schedulesRetryAtRequestedTime() {
        val job = job()
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        val resolution = DownloadFailureResolution.RetryScheduled(job)
        every { downloadJobService.retryAt(1, "throttled", retryAt) } returns resolution
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.retryAt(job, retryAt, "throttled")

        verify { downloadJobService.retryAt(1, "throttled", retryAt) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED) }
        verify { downloadAnalytics.recordRetryableFailure(job, "throttled", resolution) }
    }

    @Test
    fun failsTerminallyWithoutRetry() {
        val job = job()
        every { downloadJobService.markFailed(1, "unsupported") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failTerminal(job, "unsupported")

        verify { downloadJobService.markFailed(1, "unsupported") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.ERROR) }
        verify { downloadAnalytics.recordTerminalFailure(job, "unsupported") }
    }

    @Test
    fun failsAuthenticationRequiredWithoutRetry() {
        val job = job()
        every { downloadJobService.markFailed(1, "auth required") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failAuthenticationRequired(job, "auth required")

        verify { downloadJobService.markFailed(1, "auth required") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.AUTHENTICATION_REQUIRED) }
    }

    @Test
    fun completes() {
        val job = job()
        val result = DownloadedFileResult(
            telegramFileId = "file-id",
            telegramFileSize = 90,
            downloadedFileSize = 100,
        )
        every { downloadJobService.markCompleted(1, result) } returns job
        every { statusReporter.deleteStatus(job) } just runs

        lifecycle.complete(job, result)

        verify { downloadJobService.markCompleted(1, result) }
        verify { statusReporter.deleteStatus(job) }
    }

    private fun job(): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
        )
    }
}
