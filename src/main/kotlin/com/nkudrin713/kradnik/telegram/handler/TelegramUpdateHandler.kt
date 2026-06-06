package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.pengrad.telegrambot.model.Update
import org.springframework.stereotype.Service

@Service
class TelegramUpdateHandler(
    private val handlers: List<TelegramCommandHandler>,
    private val telegramSender: TelegramSender,
) {

    fun handle(update: Update) {
        val context = when {
            update.message()?.pinnedMessage() != null -> {
                val message = update.message()
                telegramSender.deleteMessage(
                    chatId = message.chat().id(),
                    messageId = message.messageId(),
                )
                return
            }

            update.message()?.text() != null -> {
                val message = update.message()
                TelegramUpdateContext(
                    update = update,
                    message = message,
                    callbackQuery = null,
                    text = message.text().trim(),
                    chatId = message.chat().id(),
                    messageId = message.messageId(),
                )
            }

            update.callbackQuery()?.data() != null -> {
                val callbackQuery = update.callbackQuery()
                val message = callbackQuery.message()
                TelegramUpdateContext(
                    update = update,
                    message = message,
                    callbackQuery = callbackQuery,
                    text = callbackQuery.data().trim(),
                    chatId = message.chat().id(),
                    messageId = message.messageId(),
                )
            }

            else -> return
        }

        handlers
            .first { it.supports(context) }
            .handle(context)
    }
}
