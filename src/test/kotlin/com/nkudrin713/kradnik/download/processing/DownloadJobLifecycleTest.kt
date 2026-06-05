package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test

class DownloadJobLifecycleTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val statusReporter: DownloadStatusReporter = mockk()
    private val lifecycle = DownloadJobLifecycle(
        downloadJobService = downloadJobService,
        statusReporter = statusReporter,
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
    fun failsOrRetries() {
        val job = job()
        every { downloadJobService.markFailedOrRetry(1, "error") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.failOrRetry(job, "error")

        verify { downloadJobService.markFailedOrRetry(1, "error") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.ERROR) }
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
        every { statusReporter.setStatus(any(), any()) } just runs

        lifecycle.complete(job, result)

        verify { downloadJobService.markCompleted(1, result) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.COMPLETED) }
    }

    private fun job(): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
        )
    }
}
