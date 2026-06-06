package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.telegram.TelegramSender
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.boot.ApplicationArguments
import kotlin.test.Test

class TelegramDonationPinInitializerTest {
    private val telegramSender: TelegramSender = mockk()
    private val args: ApplicationArguments = mockk()

    @Test
    fun skipsWhenDisabled() {
        initializer(enabled = false).run(args)

        verify(exactly = 0) { telegramSender.sendDonationPin(any(), any()) }
        verify(exactly = 0) { telegramSender.updateDonationPin(any(), any(), any()) }
    }

    @Test
    fun skipsWhenDonationUrlIsMissing() {
        initializer(enabled = true, donationUrl = "").run(args)

        verify(exactly = 0) { telegramSender.sendDonationPin(any(), any()) }
        verify(exactly = 0) { telegramSender.updateDonationPin(any(), any(), any()) }
    }

    @Test
    fun sendsNewPinWhenMessageIdIsMissing() {
        every { telegramSender.sendDonationPin("@mediakradnik", "https://example.com/donate") } returns 123

        initializer(enabled = true).run(args)

        verify { telegramSender.sendDonationPin("@mediakradnik", "https://example.com/donate") }
    }

    @Test
    fun updatesExistingPinWhenMessageIdIsConfigured() {
        every { telegramSender.updateDonationPin("@mediakradnik", 123, "https://example.com/donate") } just runs

        initializer(
            enabled = true,
            pinMessageId = "123",
        ).run(args)

        verify { telegramSender.updateDonationPin("@mediakradnik", 123, "https://example.com/donate") }
    }

    private fun initializer(
        enabled: Boolean,
        channelId: String = "@mediakradnik",
        donationUrl: String = "https://example.com/donate",
        pinMessageId: String = "",
    ): TelegramDonationPinInitializer {
        return TelegramDonationPinInitializer(
            telegramSender = telegramSender,
            enabled = enabled,
            channelId = channelId,
            donationUrl = donationUrl,
            pinMessageId = pinMessageId,
        )
    }
}
