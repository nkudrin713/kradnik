package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoUrlHandlerTest {
    private val downloadSettingsService: DownloadSettingsService = mockk()
    private val telegramDownloadStarter: TelegramDownloadStarter = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = VideoUrlHandler(
        downloadSettingsService = downloadSettingsService,
        telegramDownloadStarter = telegramDownloadStarter,
        telegramSender = telegramSender,
    )

    @Test
    fun supportsHttpUrls() {
        assertEquals(true, handler.supports(context("https://example.com/video")))
        assertEquals(true, handler.supports(context("http://example.com/video")))
        assertEquals(false, handler.supports(context("text")))
    }

    @Test
    fun asksWhatToDownloadInAskMode() {
        val context = context("https://example.com/video", message = message())
        every { downloadSettingsService.getMode(100) } returns DownloadMode.ASK
        every { telegramDownloadStarter.validate(100, context.text) } returns true
        every {
            telegramSender.sendDownloadChoice(
                chatId = 100,
                replyToMessageId = 200,
                telegramUpdateId = 400,
            )
        } returns 500

        handler.handle(context)

        verify {
            telegramSender.sendDownloadChoice(
                chatId = 100,
                replyToMessageId = 200,
                telegramUpdateId = 400,
            )
        }
        verify(exactly = 0) { telegramDownloadStarter.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun doesNotShowChoiceForUnsupportedUrl() {
        val context = context("https://example.com/video", message = message())
        every { downloadSettingsService.getMode(100) } returns DownloadMode.ASK
        every { telegramDownloadStarter.validate(100, context.text) } returns false

        handler.handle(context)

        verify(exactly = 0) { telegramSender.sendDownloadChoice(any(), any(), any()) }
    }

    @Test
    fun startsVideoImmediatelyInVideoMode() {
        val context = context("https://example.com/video", message = message())
        every { downloadSettingsService.getMode(100) } returns DownloadMode.VIDEO
        every {
            telegramDownloadStarter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                url = context.text,
                outputType = OutputType.VIDEO,
            )
        } returns true

        handler.handle(context)

        verify {
            telegramDownloadStarter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                url = context.text,
                outputType = OutputType.VIDEO,
            )
        }
    }

    @Test
    fun startsAudioImmediatelyInAudioMode() {
        val context = context("https://example.com/video", message = message())
        every { downloadSettingsService.getMode(100) } returns DownloadMode.AUDIO
        every {
            telegramDownloadStarter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                url = context.text,
                outputType = OutputType.AUDIO,
            )
        } returns true

        handler.handle(context)

        verify {
            telegramDownloadStarter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                url = context.text,
                outputType = OutputType.AUDIO,
            )
        }
    }

    private fun context(
        text: String,
        message: Message? = null,
    ): TelegramUpdateContext {
        val update = mockk<Update> {
            every { updateId() } returns 400
        }
        return TelegramUpdateContext(
            update = update,
            message = message,
            callbackQuery = null,
            text = text,
            chatId = 100,
            messageId = 200,
        )
    }

    private fun message(): Message {
        val user = mockk<User> {
            every { id() } returns 300
        }
        return mockk {
            every { from() } returns user
        }
    }
}
