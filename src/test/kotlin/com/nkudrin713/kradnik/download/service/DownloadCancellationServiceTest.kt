package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.processing.ActiveDownloadRegistry
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertIs

class DownloadCancellationServiceTest {
    private val jobs = mockk<DownloadJobService>()
    private val activeDownloads = mockk<ActiveDownloadRegistry>()
    private val service = DownloadCancellationService(jobs, activeDownloads)

    @Test
    fun cancelsOwnedJobAndSignalsActiveWorker() {
        val job = job()
        every { jobs.findJob(1) } returns job
        every { jobs.cancelByUser(1, 10) } returns true
        every { activeDownloads.cancel(1) } returns true

        val result = service.cancel(1, 10, TelegramMessageAddress.Chat(20, 30))

        assertIs<DownloadCancellation.Cancelled>(result)
        verify { jobs.cancelByUser(1, 10) }
        verify { activeDownloads.cancel(1) }
    }

    @Test
    fun doesNotCancelAnotherUsersJob() {
        every { jobs.findJob(1) } returns job()

        val result = service.cancel(1, 99, TelegramMessageAddress.Chat(20, 30))

        assertIs<DownloadCancellation.NotOwner>(result)
        verify(exactly = 0) { jobs.cancelByUser(any(), any()) }
        verify(exactly = 0) { activeDownloads.cancel(any()) }
    }

    @Test
    fun returnsCancelledJobForBackToOptions() {
        val job = job().apply { status = DownloadJobStatus.CANCELLED_BY_USER }
        every { jobs.findJob(1) } returns job

        val result = service.cancelledJob(1, 10, TelegramMessageAddress.Chat(20, 30))

        assertIs<DownloadJob>(result)
    }

    private fun job() = DownloadJob(
        id = 1,
        telegramUserId = 10,
        telegramChatId = 20,
        telegramStatusMessageId = 30,
    )
}
