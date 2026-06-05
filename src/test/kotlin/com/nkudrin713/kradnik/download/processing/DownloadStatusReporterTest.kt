package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test

class DownloadStatusReporterTest {
    private val telegramSender: TelegramSender = mockk()
    private val reporter = DownloadStatusReporter(telegramSender)

    @Test
    fun editsStatusMessage() {
        val job = DownloadJob(
            id = 1,
            telegramChatId = 100,
            telegramStatusMessageId = 10,
        )
        every { telegramSender.editStatus(100, 10, TelegramDownloadStatus.DOWNLOADING) } just runs

        reporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING)

        verify { telegramSender.editStatus(100, 10, TelegramDownloadStatus.DOWNLOADING) }
    }

    @Test
    fun swallowsTelegramError() {
        val job = DownloadJob(
            id = 1,
            telegramChatId = 100,
            telegramStatusMessageId = 10,
        )
        every { telegramSender.editStatus(any(), any(), any()) } throws RuntimeException("telegram error")

        reporter.setStatus(job, TelegramDownloadStatus.ERROR)

        verify { telegramSender.editStatus(100, 10, TelegramDownloadStatus.ERROR) }
    }
}
