package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test

class TelegramUpdateHandlerTest {
    private val commandHandler: TelegramCommandHandler = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = TelegramUpdateHandler(
        handlers = listOf(commandHandler),
        telegramSender = telegramSender,
    )

    @Test
    fun deletesPinServiceMessage() {
        val update = updateWithPinnedMessage(
            chatId = 100,
            messageId = 200,
        )
        every { telegramSender.deleteMessage(100, 200) } just runs

        handler.handle(update)

        verify { telegramSender.deleteMessage(100, 200) }
        verify(exactly = 0) { commandHandler.supports(any()) }
    }

    private fun updateWithPinnedMessage(chatId: Long, messageId: Int): Update {
        val chat = mockk<Chat> {
            every { id() } returns chatId
        }
        val message = mockk<Message> {
            every { pinnedMessage() } returns mockk()
            every { chat() } returns chat
            every { messageId() } returns messageId
        }

        return mockk {
            every { message() } returns message
        }
    }
}
