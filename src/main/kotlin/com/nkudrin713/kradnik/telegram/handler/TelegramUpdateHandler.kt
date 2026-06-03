package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.pengrad.telegrambot.model.Update
import org.springframework.stereotype.Service

@Service
class TelegramUpdateHandler(
    private val handlers: List<TelegramCommandHandler>,
) {

    fun handle(update: Update) {
        val message = update.message() ?: return
        val text = message.text() ?: return

        val context = TelegramUpdateContext(
            update = update,
            message = message,
            text = text.trim(),
            chatId = message.chat().id(),
        )

        handlers
            .first { it.supports(context) }
            .handle(context)
    }
}