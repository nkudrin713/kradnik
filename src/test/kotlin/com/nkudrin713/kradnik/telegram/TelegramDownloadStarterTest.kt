package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
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
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED, BotLanguage.EN)
        } returns 500
        every {
            downloadJobService.createJob(capture(command))
        } returns true

        start(OutputType.VIDEO)

        assertEquals(200, command.captured.telegramRequestMessageId)
        assertEquals(BotLanguage.EN, command.captured.language)
        assertEquals("video:telegram-video-h264-v1", command.captured.spec.cacheKey)
    }

    @Test
    fun removesDuplicateStatus() {
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED, BotLanguage.EN)
        } returns 500
        every {
            downloadJobService.createJob(any())
        } returns false
        every { telegramSender.deleteMessage(100, 500) } just runs

        start(OutputType.AUDIO)

        verify { telegramSender.deleteMessage(100, 500) }
    }

    @Test
    fun removesStatusWhenJobCreationFails() {
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED, BotLanguage.EN)
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

    @Test
    fun reusesInlineMessageAsJobStatus() {
        val command = slot<CreateDownloadJobCommand>()
        val address = TelegramMessageAddress.Inline("inline-message")
        every { telegramSender.editStatus(address, TelegramDownloadStatus.QUEUED, BotLanguage.EN) } just runs
        every { downloadJobService.createJob(capture(command)) } returns true

        start(OutputType.VIDEO, address)

        assertEquals("inline-message", command.captured.telegramInlineMessageId)
        assertEquals(null, command.captured.telegramStatusMessageId)
        verify(exactly = 0) { telegramSender.sendStatus(any(), any(), any()) }
    }

    private fun start(
        outputType: OutputType,
        messageAddress: TelegramMessageAddress = TelegramMessageAddress.Chat(100, 500),
    ) {
        starter.start(
            telegramUserId = 300,
            telegramChatId = 100,
            telegramUpdateId = 400,
            telegramRequestMessageId = 200,
            messageAddress = messageAddress,
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
