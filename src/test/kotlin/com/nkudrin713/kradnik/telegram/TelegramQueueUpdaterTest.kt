package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class TelegramQueueUpdaterTest {
    private val jobs = mockk<DownloadJobService>()
    private val sender = mockk<TelegramSender>(relaxed = true)
    private val updater = TelegramQueueUpdater(jobs, sender)

    @Test
    fun updatesOnlyChangedPositionsWithIndependentWorkloadQueues() {
        val first = job(1)
        val playlist = job(2).apply { workloadType = DownloadWorkloadType.PLAYLIST_AUDIO }
        val second = job(3).apply { telegramInlineMessageId = "inline" }
        every { jobs.queuedJobs() } returnsMany listOf(listOf(first, playlist, second), listOf(first, playlist, second), listOf(playlist, second))

        repeat(3) { updater.refresh() }

        verify(exactly = 1) { sender.editQueuedJob(TelegramMessageAddress.Chat(10, 1), 1, first.language, first.workloadType, 1, any()) }
        verify(exactly = 1) { sender.editQueuedJob(TelegramMessageAddress.Chat(10, 2), 2, playlist.language, playlist.workloadType, 1, any()) }
        verify(exactly = 1) { sender.editQueuedJob(TelegramMessageAddress.Inline("inline"), 3, second.language, second.workloadType, 2, any()) }
        verify(exactly = 1) { sender.editQueuedJob(TelegramMessageAddress.Inline("inline"), 3, second.language, second.workloadType, 1, any()) }
    }

    @Test
    fun retriesFailedEditsWithoutBlockingOtherJobs() {
        val first = job(1)
        val second = job(2)
        every { jobs.queuedJobs() } returns listOf(first, second)
        every { sender.editQueuedJob(any(), 1, any(), any(), any(), any()) } throws IllegalStateException("network") andThen Unit

        repeat(2) { updater.refresh() }

        verify(exactly = 2) { sender.editQueuedJob(any(), 1, any(), any(), any(), any()) }
        verify(exactly = 1) { sender.editQueuedJob(any(), 2, any(), any(), any(), any()) }
    }

    @Test
    fun boundsEditsPerTickAndContinuesOnNextTick() {
        every { jobs.queuedJobs() } returns (1L..25).map(::job)

        updater.refresh()
        verify(exactly = 20) { sender.editQueuedJob(any(), any(), any(), any(), any(), any()) }
        updater.refresh()
        verify(exactly = 25) { sender.editQueuedJob(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun failedOldMessagesCannotStarveLaterQueueUpdates() {
        every { jobs.queuedJobs() } returns (1L..25).map(::job)
        every { sender.editQueuedJob(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("message gone")
        repeat(2) { updater.refresh() }
        verify(atLeast = 1) { sender.editQueuedJob(any(), 25, any(), any(), any(), any()) }
    }

    private fun job(id: Long) = DownloadJob(id = id, telegramChatId = 10, telegramStatusMessageId = id.toInt())
}
