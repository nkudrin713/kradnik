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

class DonateHandlerTest {
    private val telegramSender: TelegramSender = mockk()

    @Test
    fun supportsDonateCommand() {
        val handler = handler(donationUrl = "https://example.com/donate")

        assertEquals(true, handler.supports(context("/donate")))
        assertEquals(false, handler.supports(context("/start")))
    }

    @Test
    fun sendsDonationMessage() {
        val handler = handler(donationUrl = "https://example.com/donate")
        every { telegramSender.sendDonationMessage(100, "https://example.com/donate") } just runs

        handler.handle(context("/donate"))

        verify { telegramSender.sendDonationMessage(100, "https://example.com/donate") }
    }

    @Test
    fun sendsFallbackWhenDonationUrlIsMissing() {
        val handler = handler(donationUrl = "")
        every { telegramSender.sendMessage(100, any()) } just runs

        handler.handle(context("/donate"))

        verify { telegramSender.sendMessage(100, "Донат еще не настроен. Пиратская бухгалтерия спит.") }
    }

    private fun handler(donationUrl: String): DonateHandler {
        return DonateHandler(
            telegramSender = telegramSender,
            donationUrl = donationUrl,
        )
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
