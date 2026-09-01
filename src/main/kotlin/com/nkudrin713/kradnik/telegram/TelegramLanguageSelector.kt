package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component

private const val LANGUAGE_CALLBACK_PREFIX = "lang"

@Component
class TelegramLanguageSelector(
    private val preferenceService: TelegramUserPreferenceService,
    private val messages: TelegramMessages,
    private val telegramSender: TelegramSender,
) {
    fun show(chatId: Long) {
        telegramSender.sendMessage(
            chatId = chatId,
            text = BotLanguage.entries.joinToString("\n") { language ->
                messages.text(language, TelegramMessage.LANGUAGE_PROMPT)
            },
            keyboard = keyboard(),
        )
    }

    fun handle(callbackQuery: CallbackQuery): Boolean {
        val language = parseLanguageCallback(callbackQuery.data()) ?: return false
        preferenceService.selectLanguage(callbackQuery.from().id(), language)
        telegramSender.answerCallback(
            callbackQueryId = callbackQuery.id(),
            text = messages.text(language, TelegramMessage.LANGUAGE_NAME),
        )
        callbackQuery.maybeInaccessibleMessage()?.let { message ->
            telegramSender.editMessage(
                chatId = message.chat().id(),
                messageId = message.messageId(),
                text = messages.text(language, TelegramMessage.LANGUAGE_SELECTED),
                keyboard = InlineKeyboardMarkup(),
            )
        }
        return true
    }

    private fun keyboard(): InlineKeyboardMarkup {
        val buttons = BotLanguage.entries.map { language ->
            InlineKeyboardButton(messages.text(language, TelegramMessage.LANGUAGE_NAME))
                .callbackData("$LANGUAGE_CALLBACK_PREFIX:${language.code}")
        }.toTypedArray()
        return InlineKeyboardMarkup(buttons)
    }
}

internal fun parseLanguageCallback(value: String?): BotLanguage? {
    val parts = value?.split(':', limit = 2) ?: return null
    if (parts.size != 2 || parts[0] != LANGUAGE_CALLBACK_PREFIX) {
        return null
    }
    return BotLanguage.fromCode(parts[1])
}
