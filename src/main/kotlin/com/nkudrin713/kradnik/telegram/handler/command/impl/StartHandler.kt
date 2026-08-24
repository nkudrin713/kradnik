package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.handler.TelegramMessageContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(10)
class StartHandler(
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramMessageContext): Boolean {
        return context.text == "/start"
    }

    override fun handle(context: TelegramMessageContext) {
        telegramSender.sendMessage(context.chatId, "Пришли ссылку на медиа")
    }

}
