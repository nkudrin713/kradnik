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

class LegalHandlerTest {
    private val telegramSender: TelegramSender = mockk()
    private val handler = LegalHandler(telegramSender)

    @Test
    fun supportsLegalCommand() {
        assertEquals(true, handler.supports(context("/legal")))
        assertEquals(false, handler.supports(context("/start")))
    }

    @Test
    fun sendsLegalMessage() {
        every { telegramSender.sendMessage(100, any()) } just runs

        handler.handle(context("/legal"))

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
