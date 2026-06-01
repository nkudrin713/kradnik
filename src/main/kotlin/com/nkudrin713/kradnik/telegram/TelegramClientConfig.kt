package com.nkudrin713.kradnik.telegram

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.generics.TelegramClient

@Configuration
class TelegramClientConfig {
    @Bean
    @ConditionalOnExpression("'\${telegram.bot.token:}' != ''")
    fun telegramClient(
        @Value("\${telegram.bot.token}")
        token: String,
    ): TelegramClient =
        OkHttpTelegramClient(token)
}
