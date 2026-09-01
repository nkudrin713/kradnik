package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test

class DownloadJobLifecycleTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val downloadJobService: DownloadJobService = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val lifecycle = DownloadJobLifecycle(
        downloadJobService = downloadJobService,
        telegramSender = telegramSender,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun marksDownloadingAndUploading() {
        val attempt = attempt(job())
        val uploadingJob = job(status = DownloadJobStatus.UPLOADING)
        every { telegramSender.editStatus(any<Long>(), any(), any(), any()) } just runs
        every { downloadJobService.markUploading(attempt) } returns uploadingJob

        lifecycle.markDownloading(attempt)
        lifecycle.markUploading(attempt)

        verify { telegramSender.editStatus(100, 10, TelegramDownloadStatus.DOWNLOADING, BotLanguage.EN) }
        verify { telegramSender.editStatus(100, 10, TelegramDownloadStatus.UPLOADING, BotLanguage.EN) }
    }

    @Test
    fun schedulesRetryWithExponentialBackoff() {
        val attempt = attempt(job(attempts = 2))
        val queuedJob = job(status = DownloadJobStatus.QUEUED)
        every { downloadJobService.retryAt(attempt, "error", now.plusSeconds(30)) } returns queuedJob
        every { telegramSender.editStatus(any<Long>(), any(), any(), any()) } just runs

        lifecycle.failOrRetry(attempt, "error")

        verify { telegramSender.editStatus(100, 10, TelegramDownloadStatus.QUEUED, BotLanguage.EN) }
    }

    @Test
    fun respectsLongerTelegramRetryAfter() {
        val attempt = attempt(job(attempts = 2))
        val retryAfter = Duration.ofSeconds(90)
        val queuedJob = job(status = DownloadJobStatus.QUEUED)
        every { downloadJobService.retryAt(attempt, "error", now.plus(retryAfter)) } returns queuedJob
        every { telegramSender.editStatus(any<Long>(), any(), any(), any()) } just runs

        lifecycle.failOrRetry(attempt, "error", retryAfter)

        verify { downloadJobService.retryAt(attempt, "error", now.plus(retryAfter)) }
    }

    @Test
    fun reportsTerminalFailureAfterAttemptsAreExhausted() {
        val attempt = attempt(job(attempts = 3))
        val failedJob = job(status = DownloadJobStatus.FAILED)
        every { downloadJobService.retryAt(attempt, "error", now.plusSeconds(60)) } returns failedJob
        every { telegramSender.editStatus(any<Long>(), any(), any(), any()) } just runs

        lifecycle.failOrRetry(attempt, "error")

        verify { telegramSender.editStatus(100, 10, TelegramDownloadStatus.ERROR, BotLanguage.EN) }
    }

    @Test
    fun reportsSpecificTerminalStatuses() {
        val attempt = attempt(job())
        every { downloadJobService.markFailed(attempt, any()) } returns job(status = DownloadJobStatus.FAILED)
        every { telegramSender.editStatus(any<Long>(), any(), any(), any()) } just runs

        lifecycle.rejectTooLarge(attempt, "too large")
        lifecycle.failSourceUnavailable(attempt, "missing")
        lifecycle.failAuthenticationRequired(attempt, "auth")

        verify {
            telegramSender.editStatus(100, 10, TelegramDownloadStatus.REJECTED_TOO_LARGE, BotLanguage.EN)
        }
        verify {
            telegramSender.editStatus(100, 10, TelegramDownloadStatus.SOURCE_UNAVAILABLE, BotLanguage.EN)
        }
        verify {
            telegramSender.editStatus(100, 10, TelegramDownloadStatus.AUTHENTICATION_REQUIRED, BotLanguage.EN)
        }
    }

    @Test
    fun completesJobAndDeletesStatus() {
        val attempt = attempt(job())
        val completedJob = job(status = DownloadJobStatus.COMPLETED)
        every {
            downloadJobService.markCompleted(attempt, "file-id")
        } returns completedJob
        every { telegramSender.deleteMessage(100, 10) } just runs

        lifecycle.complete(
            attempt = attempt,
            telegramFileId = "file-id",
        )

        verify { telegramSender.deleteMessage(100, 10) }
    }

    @Test
    fun editsInlineStatusAndKeepsCompletedGuestMessage() {
        val inlineJob = job().apply {
            telegramStatusMessageId = null
            telegramInlineMessageId = "inline-message"
        }
        val attempt = attempt(inlineJob)
        val completedJob = job(status = DownloadJobStatus.COMPLETED).apply {
            telegramStatusMessageId = null
            telegramInlineMessageId = "inline-message"
        }
        every {
            telegramSender.editStatus(
                TelegramMessageAddress.Inline("inline-message"),
                TelegramDownloadStatus.DOWNLOADING,
                BotLanguage.EN,
            )
        } just runs
        every { downloadJobService.markCompleted(attempt, "file-id") } returns completedJob

        lifecycle.markDownloading(attempt)
        lifecycle.complete(attempt, "file-id")

        verify {
            telegramSender.editStatus(
                TelegramMessageAddress.Inline("inline-message"),
                TelegramDownloadStatus.DOWNLOADING,
                BotLanguage.EN,
            )
        }
        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
    }

    @Test
    fun ignoresStatusDeliveryErrors() {
        val job = job().apply { telegramStatusMessageId = null }
        every {
            telegramSender.editStatus(any<Long>(), any(), any(), any())
        } throws RuntimeException("Telegram error")
        every { downloadJobService.markCompleted(any(), "file-id") } returns job

        lifecycle.markDownloading(attempt(job))
        lifecycle.complete(
            attempt = attempt(job),
            telegramFileId = "file-id",
        )

        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
    }

    private fun job(
        attempts: Int = 0,
        status: DownloadJobStatus = DownloadJobStatus.PROCESSING,
    ): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
            telegramStatusMessageId = 10,
            attempts = attempts,
            status = status,
        )
    }

    private fun attempt(job: DownloadJob): ClaimedDownloadJob {
        return ClaimedDownloadJob(job, UUID.fromString("00000000-0000-0000-0000-000000000001"))
    }
}
