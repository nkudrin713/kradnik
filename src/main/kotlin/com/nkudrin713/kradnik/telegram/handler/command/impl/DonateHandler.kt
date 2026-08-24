package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramMessageContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val DONATE_COMMAND = "/donate"

@Component
@Order(20)
class DonateHandler(
    private val telegramSender: TelegramSender,
    private val telegramDonationSender: TelegramDonationSender,
    @Value("\${telegram.donation.url:}")
    private val donationUrl: String,
) : TelegramCommandHandler {

    override fun supports(context: TelegramMessageContext): Boolean {
        return context.text == DONATE_COMMAND
    }

    override fun handle(context: TelegramMessageContext) {
        if (donationUrl.isBlank()) {
            telegramSender.sendMessage(context.chatId, "Донат еще не настроен. Пиратская бухгалтерия спит.")
            return
        }

        telegramDonationSender.sendMessage(context.chatId, donationUrl)
    }
}
