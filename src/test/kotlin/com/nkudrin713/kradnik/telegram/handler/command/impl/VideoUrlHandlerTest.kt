package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.handler.TelegramMessageContext
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
    private val coordinator: DownloadChoiceCoordinator = mockk()
    private val handler = VideoUrlHandler(coordinator)

    @Test
    fun supportsHttpUrls() {
        assertEquals(true, handler.supports(context("https://example.com/video")))
        assertEquals(true, handler.supports(context("http://example.com/video")))
        assertEquals(false, handler.supports(context("text")))
    }

    @Test
    fun alwaysPreparesDownloadChoiceForUrl() {
        val context = context("https://example.com/video", message())
        every { coordinator.prepare(any()) } just runs

        handler.handle(context)

        verify {
            coordinator.prepare(
                PrepareDownloadChoiceCommand(
                    telegramUserId = 300,
                    telegramChatId = 100,
                    telegramUpdateId = 400,
                    telegramRequestMessageId = 200,
                    url = "https://example.com/video",
                )
            )
        }
    }

    private fun context(
        text: String,
        message: Message = mockk(relaxed = true),
    ): TelegramMessageContext {
        val update = mockk<Update> { every { updateId() } returns 400 }
        return TelegramMessageContext(
            update = update,
            message = message,
            text = text,
            chatId = 100,
            messageId = 200,
        )
    }

    private fun message(): Message {
        return mockk {
            every { from() } returns mockk<User> { every { id() } returns 300 }
        }
    }
}
