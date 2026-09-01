package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class TelegramDonationPinInitializer(
    private val telegramDonationSender: TelegramDonationSender,
    @Value("\${telegram.donation.pin.enabled:false}")
    private val enabled: Boolean,
    @Value("\${telegram.donation.channel-id:@mediakradnik}")
    private val channelId: String,
    @Value("\${telegram.donation.url:}")
    private val donationUrl: String,
    @Value("\${telegram.donation.pin-message-id:}")
    private val pinMessageId: String,
    @Value("\${telegram.donation.pin-language:en}")
    private val pinLanguage: String = "en",
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            return
        }

        if (donationUrl.isBlank()) {
            logger.warn("Telegram donation pin skipped: telegram.donation.url is empty")
            return
        }

        runCatching {
            val language = BotLanguage.fromCode(pinLanguage) ?: BotLanguage.EN
            val messageId = pinMessageId.toIntOrNull()
            if (messageId == null) {
                val createdMessageId = telegramDonationSender.sendPin(channelId, donationUrl, language)
                logger.info("Telegram donation pin created: channel={}, messageId={}", channelId, createdMessageId)
            } else {
                telegramDonationSender.updatePin(channelId, messageId, donationUrl, language)
                logger.info("Telegram donation pin updated: channel={}, messageId={}", channelId, messageId)
            }
        }.onFailure {
            logger.warn("Telegram donation pin failed: {}", it.message)
        }
    }
}
