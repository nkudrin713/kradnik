package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Int.MAX_VALUE)
class UnknownMessageHandler(
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return true
    }

    override fun handle(context: TelegramUpdateContext) {
        telegramSender.sendMessage(context.chatId, "Нужна ссылка")
    }
}
