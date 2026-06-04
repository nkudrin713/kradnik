package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import org.springframework.stereotype.Component

@Component
class TelegramTextMessenger(
    private val bot: TelegramBot,
) {

    fun send(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ): Int {
        val request = SendMessage(chatId, text)
        if (keyboard != null) {
            request.replyMarkup(keyboard)
        }

        val response = bot.execute(request)

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }

        return requireNotNull(response.message()).messageId()
    }

    fun edit(
        chatId: Long,
        messageId: Int,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ) {
        val request = EditMessageText(chatId, messageId, text)
        if (keyboard != null) {
            request.replyMarkup(keyboard)
        }

        val response = bot.execute(request)

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }
    }

    fun answerCallback(callbackQueryId: String) {
        val response = bot.execute(AnswerCallbackQuery(callbackQueryId))

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }
    }
}
