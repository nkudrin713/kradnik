package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
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
import java.util.UUID
import kotlin.test.Test

class DownloadJobLifecycleTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val statusReporter: DownloadStatusReporter = mockk()
    private val downloadAnalytics: DownloadAnalytics = mockk(relaxed = true)
    private val retryPolicy: DownloadRetryPolicy = mockk()
    private val lifecycle = DownloadJobLifecycle(
        downloadJobService = downloadJobService,
        statusReporter = statusReporter,
        downloadAnalytics = downloadAnalytics,
        retryPolicy = retryPolicy,
    )

    @Test
    fun marksDownloading() {
        val job = job()
        val attempt = attempt(job)
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.markDownloading(attempt)

        verify { statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING) }
    }

    @Test
    fun marksUploading() {
        val job = job()
        val attempt = attempt(job)
        every { downloadJobService.markUploading(attempt) } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.markUploading(attempt)

        verify { downloadJobService.markUploading(attempt) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING) }
    }

    @Test
    fun rejectsTooLarge() {
        val job = job()
        val attempt = attempt(job)
        every { downloadJobService.markFailed(attempt, "too large") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.rejectTooLarge(attempt, "too large")

        verify { downloadJobService.markFailed(attempt, "too large") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.REJECTED_TOO_LARGE) }
    }

    @Test
    fun reportsQueuedStatusWhenRetryIsScheduled() {
        val job = job()
        val attempt = attempt(job)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { retryPolicy.retryAt(job.attempts, null) } returns retryAt
        every { downloadJobService.retryAt(attempt, "error", retryAt) } returns
                DownloadFailureResolution.RetryScheduled(job)
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failOrRetry(attempt, "error")

        verify { downloadJobService.retryAt(attempt, "error", retryAt) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED) }
    }

    @Test
    fun reportsErrorStatusWhenAttemptsAreExhausted() {
        val job = job()
        val attempt = attempt(job)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { retryPolicy.retryAt(job.attempts, null) } returns retryAt
        every { downloadJobService.retryAt(attempt, "error", retryAt) } returns
                DownloadFailureResolution.TerminalFailure(job)
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failOrRetry(attempt, "error")

        verify { statusReporter.setStatus(job, TelegramDownloadStatus.ERROR) }
    }

    @Test
    fun defersBeforeAttemptAndKeepsQueuedStatus() {
        val job = job()
        val attempt = attempt(job)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { downloadJobService.deferBeforeAttempt(attempt, retryAt, "rate limited") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.deferBeforeAttempt(attempt, retryAt, "rate limited")

        verify { downloadJobService.deferBeforeAttempt(attempt, retryAt, "rate limited") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED) }
    }

    @Test
    fun schedulesRetryAtRequestedTime() {
        val job = job()
        val attempt = attempt(job)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        val resolution = DownloadFailureResolution.RetryScheduled(job)
        every { downloadJobService.retryAt(attempt, "throttled", retryAt) } returns resolution
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.retryAt(attempt, retryAt, "throttled")

        verify { downloadJobService.retryAt(attempt, "throttled", retryAt) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.QUEUED) }
        verify { downloadAnalytics.recordRetryableFailure(job, "throttled", resolution) }
    }

    @Test
    fun failsTerminallyWithoutRetry() {
        val job = job()
        val attempt = attempt(job)
        every { downloadJobService.markFailed(attempt, "unsupported") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failTerminal(attempt, "unsupported")

        verify { downloadJobService.markFailed(attempt, "unsupported") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.ERROR) }
        verify { downloadAnalytics.recordTerminalFailure(job, "unsupported") }
    }

    @Test
    fun failsAuthenticationRequiredWithoutRetry() {
        val job = job()
        val attempt = attempt(job)
        every { downloadJobService.markFailed(attempt, "auth required") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failAuthenticationRequired(attempt, "auth required")

        verify { downloadJobService.markFailed(attempt, "auth required") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.AUTHENTICATION_REQUIRED) }
    }

    @Test
    fun completes() {
        val job = job()
        val attempt = attempt(job)
        val result = DownloadedFileResult(
            telegramFileId = "file-id",
            telegramFileSize = 90,
            downloadedFileSize = 100,
        )
        every { downloadJobService.markCompleted(attempt, result) } returns job
        every { statusReporter.deleteStatus(job) } just runs

        lifecycle.complete(attempt, result)

        verify { downloadJobService.markCompleted(attempt, result) }
        verify { statusReporter.deleteStatus(job) }
    }

    private fun job(): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
        )
    }

    private fun attempt(job: DownloadJob): ClaimedDownloadJob =
        ClaimedDownloadJob(job, UUID.randomUUID())
}
