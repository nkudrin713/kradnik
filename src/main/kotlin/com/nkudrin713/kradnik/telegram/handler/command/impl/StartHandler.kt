package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(10)
class StartHandler(
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text == "/start"
    }

    override fun handle(context: TelegramUpdateContext) {
        telegramSender.sendMessage(context.chatId, "Пришли ссылку на видео")
    }

}
