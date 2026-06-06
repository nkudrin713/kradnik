package com.nkudrin713.kradnik.telegram.config

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.BotCommand
import com.pengrad.telegrambot.request.SetMyCommands
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class TelegramCommandsInitializer(
    private val bot: TelegramBot,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val response = bot.execute(
            SetMyCommands(
                BotCommand("start", "запустить бота"),
                BotCommand("mode", "переключить видео/аудио"),
                BotCommand("help", "что умеет бот"),
                BotCommand("legal", "правовой дисклеймер"),
                BotCommand("donate", "поддержать проект"),
            )
        )

        if (!response.isOk) {
            logger.warn("Telegram commands registration failed: {}", response.description())
        }
    }
}
