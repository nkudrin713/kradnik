package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class HelpHandlerTest {
    private val telegramSender: TelegramSender = mockk()
    private val handler = HelpHandler(telegramSender)

    @Test
    fun supportsHelpCommand() {
        assertEquals(true, handler.supports(context("/help")))
        assertEquals(false, handler.supports(context("/start")))
    }

    @Test
    fun sendsHelpMessage() {
        every { telegramSender.sendMessage(100, any()) } just runs

        handler.handle(context("/help"))

        verify { telegramSender.sendMessage(100, any()) }
    }

    private fun context(text: String): TelegramUpdateContext {
        return TelegramUpdateContext(
            update = mockk(),
            message = null,
            callbackQuery = null,
            text = text,
            chatId = 100,
            messageId = null,
        )
    }
}
