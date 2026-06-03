package com.nkudrin713.kradnik.telegram.handler

import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update

data class TelegramUpdateContext(
    val update: Update,
    val message: Message?,
    val callbackQuery: CallbackQuery?,
    val text: String,
    val chatId: Long,
    val messageId: Int?,
)
