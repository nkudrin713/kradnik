package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.CreateDownloadJobResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramDownloadStarterTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val starter = TelegramDownloadStarter(
        downloadJobService = downloadJobService,
        telegramSender = telegramSender,
    )

    @Test
    fun createsJobWithRequestMessage() {
        val command = slot<CreateDownloadJobCommand>()
        val job = DownloadJob(id = 1)
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED)
        } returns 500
        every {
            downloadJobService.createJob(capture(command))
        } returns CreateDownloadJobResult.Created(job)

        val started = start(OutputType.VIDEO)

        assertEquals(true, started)
        assertEquals(200, command.captured.telegramRequestMessageId)
        assertEquals("video:telegram-video-h264-v1", command.captured.spec.cacheKey)
    }

    @Test
    fun removesDuplicateStatus() {
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED)
        } returns 500
        every {
            downloadJobService.createJob(any())
        } returns CreateDownloadJobResult.Existing(DownloadJob(id = 1))
        every { telegramSender.deleteMessage(100, 500) } just runs

        val started = start(OutputType.AUDIO)

        assertEquals(true, started)
        verify { telegramSender.deleteMessage(100, 500) }
    }

    @Test
    fun removesStatusWhenJobCreationFails() {
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED)
        } returns 500
        every {
            downloadJobService.createJob(any())
        } throws IllegalStateException("database error")
        every { telegramSender.deleteMessage(100, 500) } just runs

        assertFailsWith<IllegalStateException> {
            start(OutputType.VIDEO)
        }

        verify { telegramSender.deleteMessage(100, 500) }
    }

    private fun start(outputType: OutputType): Boolean {
        return starter.start(
            telegramUserId = 300,
            telegramChatId = 100,
            telegramUpdateId = 400,
            telegramRequestMessageId = 200,
            spec = spec(outputType),
        )
    }

    private fun spec(outputType: OutputType): DownloadSpec {
        return DownloadSpec(
            originalUrl = URL,
            normalizedUrl = URL,
            cacheKey = "video",
            outputType = outputType,
            platform = DownloadPlatform.YOUTUBE,
            formatSelector = "format",
            presetName = "preset",
        )
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
