package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCallbackHandler
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.pengrad.telegrambot.model.CallbackQuery
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
    private val callbackHandler: TelegramCallbackHandler = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = TelegramUpdateHandler(
        commandHandlers = listOf(commandHandler),
        callbackHandlers = listOf(callbackHandler),
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
        verify(exactly = 0) { callbackHandler.supports(any()) }
    }

    @Test
    fun routesTextOnlyToCommandHandlers() {
        val update = textUpdate("dl:token:option")
        every { commandHandler.supports(any()) } returns true
        every { commandHandler.handle(any()) } just runs

        handler.handle(update)

        verify { commandHandler.handle(match { it.text == "dl:token:option" }) }
        verify(exactly = 0) { callbackHandler.supports(any()) }
    }

    @Test
    fun routesCallbacksOnlyToCallbackHandlers() {
        val update = callbackUpdate("dl:token:option")
        every { callbackHandler.supports(any()) } returns true
        every { callbackHandler.handle(any()) } just runs

        handler.handle(update)

        verify { callbackHandler.handle(match { it.text == "dl:token:option" }) }
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

    private fun textUpdate(text: String): Update {
        val chat = mockk<Chat> { every { id() } returns 100 }
        val message = mockk<Message> {
            every { pinnedMessage() } returns null
            every { text() } returns text
            every { chat() } returns chat
            every { messageId() } returns 200
        }
        return mockk { every { message() } returns message }
    }

    private fun callbackUpdate(data: String): Update {
        val chat = mockk<Chat> { every { id() } returns 100 }
        val message = mockk<Message> {
            every { chat() } returns chat
            every { messageId() } returns 200
        }
        val callbackQuery = mockk<CallbackQuery> {
            every { data() } returns data
            every { message() } returns message
        }
        return mockk {
            every { message() } returns null
            every { callbackQuery() } returns callbackQuery
        }
    }
}
