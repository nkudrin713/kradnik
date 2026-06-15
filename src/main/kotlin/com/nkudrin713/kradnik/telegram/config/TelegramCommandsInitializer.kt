package com.nkudrin713.kradnik.telegram.config

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.BotCommand
import com.pengrad.telegrambot.model.botcommandscope.BotCommandScope
import com.pengrad.telegrambot.model.botcommandscope.BotCommandScopeAllPrivateChats
import com.pengrad.telegrambot.request.DeleteMyCommands
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
    private val commandScopes = listOf(null, BotCommandScopeAllPrivateChats())

    override fun run(args: ApplicationArguments) {
        commandScopes.forEach { scope ->
            deleteCommands(scope)
            setCommands(scope)
        }
    }

    private fun deleteCommands(scope: BotCommandScope?) {
        val request = DeleteMyCommands().withScope(scope)
        val deleteResponse = bot.execute(request)
        if (!deleteResponse.isOk) {
            logger.warn("Telegram commands deletion failed for scope {}: {}", scope.name(), deleteResponse.description())
        }
    }

    private fun setCommands(scope: BotCommandScope?) {
        val request = SetMyCommands(*commands()).withScope(scope)
        val response = bot.execute(request)

        if (!response.isOk) {
            logger.warn("Telegram commands registration failed for scope {}: {}", scope.name(), response.description())
        }
    }

    private fun commands(): Array<BotCommand> {
        return arrayOf(
            BotCommand("start", "запустить бота"),
            BotCommand("mode", "переключить видео/аудио"),
            BotCommand("help", "что умеет бот"),
            BotCommand("legal", "правовой дисклеймер"),
            BotCommand("donate", "поддержать проект"),
        )
    }

    private fun DeleteMyCommands.withScope(scope: BotCommandScope?): DeleteMyCommands {
        return scope?.let { scope(it) } ?: this
    }

    private fun SetMyCommands.withScope(scope: BotCommandScope?): SetMyCommands {
        return scope?.let { scope(it) } ?: this
    }

    private fun BotCommandScope?.name(): String {
        return this?.type ?: "default"
    }
}
