package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
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
    private val messages: Messages,
) {
    fun sendMessage(chatId: Long, donationUrl: String, language: BotLanguage = BotLanguage.EN) {
        apiClient.execute(
            SendMessage(chatId, messages.text(language, MessageKey.DONATION_MESSAGE))
                .replyMarkup(donationKeyboard(donationUrl, language)),
        )
    }

    fun sendPin(channelId: String, donationUrl: String, language: BotLanguage = BotLanguage.EN): Int {
        val response = apiClient.execute(
            SendMessage(channelId, messages.text(language, MessageKey.DONATION_PIN))
                .replyMarkup(donationKeyboard(donationUrl, language)),
        )
        val messageId = response.message()?.messageId()
            ?: throw TelegramSendException("Telegram response does not contain donation message")
        pinMessage(channelId, messageId)
        return messageId
    }

    fun updatePin(
        channelId: String,
        messageId: Int,
        donationUrl: String,
        language: BotLanguage = BotLanguage.EN,
    ) {
        editPin(channelId, messageId, donationUrl, language)
        unpinMessage(channelId, messageId)
        pinMessage(channelId, messageId)
    }

    private fun editPin(channelId: String, messageId: Int, donationUrl: String, language: BotLanguage) {
        try {
            apiClient.execute(
                EditMessageText(channelId, messageId, messages.text(language, MessageKey.DONATION_PIN))
                    .replyMarkup(donationKeyboard(donationUrl, language)),
            )
        } catch (error: TelegramSendException) {
            if (error.kind != TelegramSendFailureKind.MESSAGE_NOT_MODIFIED) {
                throw error
            }
        }
    }

    private fun donationKeyboard(donationUrl: String, language: BotLanguage): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            InlineKeyboardButton(messages.text(language, MessageKey.DONATION_BUTTON)).url(donationUrl).style("success"),
        )
    }

    private fun pinMessage(channelId: String, messageId: Int) {
        apiClient.execute(
            PinChatMessage(channelId, messageId).disableNotification(true),
        )
    }

    private fun unpinMessage(channelId: String, messageId: Int) {
        apiClient.execute(
            UnpinChatMessage(channelId).messageId(messageId),
        )
    }
}
