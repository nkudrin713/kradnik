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
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse

class JobLifecycleTest {
    private val jobs = mockk<DownloadJobService>(relaxed = true)
    private val sender = mockk<TelegramSender>(relaxed = true)
    private val lifecycle = JobLifecycle(jobs, TelegramJobProgress(sender, telegramMessages()))

    @Test
    fun failureCannotOverwriteCancellationOrItsStatus() {
        val job = DownloadJob(id = 1, telegramChatId = 2, telegramStatusMessageId = 3)
        every { jobs.markFailed(job, any()) } returns false
        every { jobs.markCompleted(job, any()) } returns false
        lifecycle.fail(job, DownloadFailure(DownloadFailureReason.TOO_LARGE, "limit"))
        assertFalse(lifecycle.complete(job, "file"))
        verify(exactly = 0) { sender.editStatus(any<Long>(), any(), any(), any()) }
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
        verify { sender.editStatus(2, 3, TelegramDownloadStatus.AUTHENTICATION_REQUIRED, single.language) }
        verify { sender.editStatus(TelegramMessageAddress.Chat(2, 4), TelegramDownloadStatus.ERROR, playlist.language) }
    }
}
