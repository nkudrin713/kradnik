package com.nkudrin713.kradnik.telegram

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.objects.Update

@Component
@ConditionalOnExpression("'\${telegram.bot.token:}' != ''")
class KradnikTelegramBot(
    @Value("\${telegram.bot.token}")
    private val token: String,
    private val telegramUpdateHandler: TelegramUpdateHandler,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {
    override fun getBotToken(): String =
        token

    override fun getUpdatesConsumer(): LongPollingSingleThreadUpdateConsumer =
        this

    override fun consume(update: Update) {
        telegramUpdateHandler.handle(update)
    }
}
