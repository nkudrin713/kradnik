package com.nkudrin713.kradnik.telegram.config

import com.pengrad.telegrambot.TelegramBot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramBotConfig(
    @Value("\${telegram.bot.token}")
    private val token: String
) {
    @Bean
    fun telegramBot(): TelegramBot {
        return TelegramBot(token)
    }
}