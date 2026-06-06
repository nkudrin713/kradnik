package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val DONATE_COMMAND = "/donate"

@Component
@Order(20)
class DonateHandler(
    private val telegramSender: TelegramSender,
    @Value("\${telegram.donation.url:}")
    private val donationUrl: String,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text == DONATE_COMMAND
    }

    override fun handle(context: TelegramUpdateContext) {
        if (donationUrl.isBlank()) {
            telegramSender.sendMessage(context.chatId, "Донат еще не настроен. Пиратская бухгалтерия спит.")
            return
        }

        telegramSender.sendDonationMessage(context.chatId, donationUrl)
    }
}
