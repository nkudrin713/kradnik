package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCallbackHandler
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.pengrad.telegrambot.model.Update
import org.springframework.stereotype.Service

@Service
class TelegramUpdateHandler(
    private val commandHandlers: List<TelegramCommandHandler>,
    private val callbackHandlers: List<TelegramCallbackHandler>,
    private val telegramSender: TelegramSender,
) {

    fun handle(update: Update) {
        when {
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
                val context = TelegramMessageContext(
                    update = update,
                    message = message,
                    text = message.text().trim(),
                    chatId = message.chat().id(),
                    messageId = message.messageId(),
                )
                commandHandlers
                    .first { it.supports(context) }
                    .handle(context)
            }

            update.callbackQuery()?.data() != null -> {
                val callbackQuery = update.callbackQuery()
                val message = callbackQuery.message()
                val context = TelegramCallbackContext(
                    update = update,
                    callbackQuery = callbackQuery,
                    text = callbackQuery.data().trim(),
                    chatId = message.chat().id(),
                    messageId = message.messageId(),
                )
                callbackHandlers
                    .firstOrNull { it.supports(context) }
                    ?.handle(context)
            }

            else -> return
        }
    }
}
