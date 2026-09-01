package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.boot.ApplicationArguments
import kotlin.test.Test

class TelegramDonationPinInitializerTest {
    private val telegramDonationSender: TelegramDonationSender = mockk()
    private val args: ApplicationArguments = mockk()

    @Test
    fun skipsWhenDisabled() {
        initializer(enabled = false).run(args)

        verify(exactly = 0) { telegramDonationSender.sendPin(any(), any(), any()) }
        verify(exactly = 0) { telegramDonationSender.updatePin(any(), any(), any(), any()) }
    }

    @Test
    fun skipsWhenDonationUrlIsMissing() {
        initializer(enabled = true, donationUrl = "").run(args)

        verify(exactly = 0) { telegramDonationSender.sendPin(any(), any(), any()) }
        verify(exactly = 0) { telegramDonationSender.updatePin(any(), any(), any(), any()) }
    }

    @Test
    fun sendsNewPinWhenMessageIdIsMissing() {
        every {
            telegramDonationSender.sendPin("@mediakradnik", "https://example.com/donate", BotLanguage.EN)
        } returns 123

        initializer(enabled = true).run(args)

        verify {
            telegramDonationSender.sendPin("@mediakradnik", "https://example.com/donate", BotLanguage.EN)
        }
    }

    @Test
    fun updatesExistingPinWhenMessageIdIsConfigured() {
        every {
            telegramDonationSender.updatePin("@mediakradnik", 123, "https://example.com/donate", BotLanguage.EN)
        } just runs

        initializer(
            enabled = true,
            pinMessageId = "123",
        ).run(args)

        verify {
            telegramDonationSender.updatePin("@mediakradnik", 123, "https://example.com/donate", BotLanguage.EN)
        }
    }

    private fun initializer(
        enabled: Boolean,
        channelId: String = "@mediakradnik",
        donationUrl: String = "https://example.com/donate",
        pinMessageId: String = "",
    ): TelegramDonationPinInitializer {
        return TelegramDonationPinInitializer(
            telegramDonationSender = telegramDonationSender,
            enabled = enabled,
            channelId = channelId,
            donationUrl = donationUrl,
            pinMessageId = pinMessageId,
        )
    }
}
