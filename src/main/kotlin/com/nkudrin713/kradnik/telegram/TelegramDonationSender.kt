package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.PinChatMessage
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.UnpinChatMessage
import org.springframework.stereotype.Component

@Component
class TelegramDonationSender(
    private val apiClient: TelegramApiClient,
) {
    fun sendMessage(chatId: Long, donationUrl: String) {
        apiClient.execute(
            SendMessage(chatId, DONATION_MESSAGE)
                .replyMarkup(donationKeyboard(donationUrl))
        )
    }

    fun sendPin(channelId: String, donationUrl: String): Int {
        val response = apiClient.execute(
            SendMessage(channelId, DONATION_PIN_TEXT)
                .replyMarkup(donationKeyboard(donationUrl))
        )
        val messageId = response.message()?.messageId()
            ?: throw TelegramSendException("Telegram response does not contain donation message")
        pinMessage(channelId, messageId)
        return messageId
    }

    fun updatePin(channelId: String, messageId: Int, donationUrl: String) {
        editPin(channelId, messageId, donationUrl)
        unpinMessage(channelId, messageId)
        pinMessage(channelId, messageId)
    }

    private fun editPin(channelId: String, messageId: Int, donationUrl: String) {
        try {
            apiClient.execute(
                EditMessageText(channelId, messageId, DONATION_PIN_TEXT)
                    .replyMarkup(donationKeyboard(donationUrl))
            )
        } catch (error: TelegramSendException) {
            if (error.kind != TelegramSendFailureKind.MESSAGE_NOT_MODIFIED) {
                throw error
            }
        }
    }

    private fun donationKeyboard(donationUrl: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            InlineKeyboardButton(DONATION_BUTTON_TEXT).url(donationUrl)
        )
    }

    private fun pinMessage(channelId: String, messageId: Int) {
        apiClient.execute(
            PinChatMessage(channelId, messageId).disableNotification(true)
        )
    }

    private fun unpinMessage(channelId: String, messageId: Int) {
        apiClient.execute(
            UnpinChatMessage(channelId).messageId(messageId)
        )
    }

    private companion object {
        private val DONATION_MESSAGE = """
            Крадник остаётся бесплатным.

            А это — банка для тех, кто хочет подкинуть топлива проекту.
            Донаты уходят на хостинг, новые фичи и моральную устойчивость разработчика.
            Обещаю не покупать пиво и сигареты.

            Спасибо, что помогаете боту жить.
        """.trimIndent()
        private const val DONATION_PIN_TEXT = "Поддержать проект можно нажав на кнопку. Больше инфы в /donate"
        private const val DONATION_BUTTON_TEXT = "Поддержать 💸"
    }
}
