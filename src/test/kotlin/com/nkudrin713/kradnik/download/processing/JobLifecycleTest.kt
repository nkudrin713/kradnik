package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse

class JobLifecycleTest {
    private val jobs = mockk<DownloadJobService>(relaxed = true)
    private val sender = mockk<TelegramSender>(relaxed = true)
    private val lifecycle = JobLifecycle(jobs, TelegramJobProgress(sender, telegramMessages(), jobs, mockk(relaxed = true)))

    @Test
    fun failureCannotOverwriteCancellationOrItsStatus() {
        val job = DownloadJob(id = 1, telegramChatId = 2, telegramStatusMessageId = 3)
        every { jobs.markFailed(job, any()) } returns false
        every { jobs.markCompleted(job, any()) } returns false
        lifecycle.fail(job, DownloadFailure(DownloadFailureReason.TOO_LARGE, "limit"))
        assertFalse(lifecycle.complete(job, "file"))
        verify(exactly = 0) { sender.editStatus(any<Long>(), any(), any(), any()) }
        verify(exactly = 0) { sender.editFailure(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { sender.deleteMessage(any(), any()) }
    }

    @Test
    fun sourceFailureHasDifferentSingleAndPlaylistPresentation() {
        val single = DownloadJob(id = 1, telegramChatId = 2, telegramStatusMessageId = 3)
        val playlist = DownloadJob(id = 2, telegramChatId = 2, telegramStatusMessageId = 4, workloadType = DownloadWorkloadType.PLAYLIST_AUDIO)
        every { jobs.markFailed(any(), any()) } returns true
        val failure = DownloadFailure(DownloadFailureReason.AUTHENTICATION_REQUIRED, "login")
        lifecycle.fail(single, failure)
        lifecycle.fail(playlist, failure)
        verify { sender.editFailure(TelegramMessageAddress.Chat(2, 3), telegramMessages().text(single.language, TelegramDownloadStatus.AUTHENTICATION_REQUIRED.message), any(), single.language, 1) }
        verify { sender.editStatus(TelegramMessageAddress.Chat(2, 4), TelegramDownloadStatus.ERROR, playlist.language) }
    }

    @Test
    fun actionableFailuresReachChatAndInlineStatusesInEveryLanguage() {
        every { jobs.markFailed(any(), any()) } returns true
        val statuses = mapOf(
            DownloadFailureReason.FORMAT_UNAVAILABLE to TelegramDownloadStatus.FORMAT_UNAVAILABLE,
            DownloadFailureReason.AUTHENTICATION_REQUIRED to TelegramDownloadStatus.AUTHENTICATION_REQUIRED,
            DownloadFailureReason.SOURCE_UNAVAILABLE to TelegramDownloadStatus.SOURCE_UNAVAILABLE,
            DownloadFailureReason.SOURCE_RATE_LIMITED to TelegramDownloadStatus.SOURCE_RATE_LIMITED,
            DownloadFailureReason.SOURCE_REQUEST_FAILED to TelegramDownloadStatus.SOURCE_REQUEST_FAILED,
            DownloadFailureReason.SOURCE_TIMEOUT to TelegramDownloadStatus.SOURCE_TIMEOUT,
            DownloadFailureReason.METADATA_UNAVAILABLE to TelegramDownloadStatus.METADATA_UNAVAILABLE,
        )
        for (language in BotLanguage.entries) {
            for ((reason, status) in statuses) {
                val chat = DownloadJob(id = 1, telegramChatId = 2, telegramStatusMessageId = 3, language = language)
                val inline = DownloadJob(id = 2, telegramInlineMessageId = "inline", language = language)
                lifecycle.fail(chat, DownloadFailure(reason, "Private diagnostic"))
                lifecycle.fail(inline, DownloadFailure(reason, "Private diagnostic"))
                verify { sender.editFailure(TelegramMessageAddress.Chat(2, 3), telegramMessages().text(language, status.message), any(), language, 1) }
                verify { sender.editFailure(TelegramMessageAddress.Inline("inline"), telegramMessages().text(language, status.message), any(), language, 2) }
            }
        }
    }
}
