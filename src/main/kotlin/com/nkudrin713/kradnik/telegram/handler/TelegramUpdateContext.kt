package com.nkudrin713.kradnik.telegram.handler

import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update

data class TelegramMessageContext(
    val update: Update,
    val message: Message,
    val text: String,
    val chatId: Long,
    val messageId: Int,
)

data class TelegramCallbackContext(
    val update: Update,
    val callbackQuery: CallbackQuery,
    val text: String,
    val chatId: Long,
    val messageId: Int,
)
