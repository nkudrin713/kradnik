package com.nkudrin713.kradnik.telegram.config

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.BotCommand
import com.pengrad.telegrambot.model.botcommandscope.BotCommandScope
import com.pengrad.telegrambot.model.botcommandscope.BotCommandScopeAllPrivateChats
import com.pengrad.telegrambot.request.DeleteMyCommands
import com.pengrad.telegrambot.request.SetMyCommands
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class TelegramCommandsInitializer(
    private val bot: TelegramBot,
    private val messages: TelegramMessages,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val commandScopes = listOf(null, BotCommandScopeAllPrivateChats())

    override fun run(args: ApplicationArguments) {
        commandScopes.forEach { scope ->
            commandLanguages.forEach { language ->
                deleteCommands(scope, language)
                setCommands(scope, language)
            }
        }
    }

    private fun deleteCommands(scope: BotCommandScope?, language: BotLanguage?) {
        val request = DeleteMyCommands().withScope(scope).withLanguage(language)
        val deleteResponse = bot.execute(request)
        if (!deleteResponse.isOk) {
            logger.warn("Telegram commands deletion failed for scope {}: {}", scope.name(), deleteResponse.description())
        }
    }

    private fun setCommands(scope: BotCommandScope?, language: BotLanguage?) {
        val commandLanguage = language ?: BotLanguage.EN
        val request = SetMyCommands(*commands(commandLanguage))
            .withScope(scope)
            .withLanguage(language)
        val response = bot.execute(request)

        if (!response.isOk) {
            logger.warn("Telegram commands registration failed for scope {}: {}", scope.name(), response.description())
        }
    }

    private fun commands(language: BotLanguage): Array<BotCommand> {
        return arrayOf(
            BotCommand("start", messages.text(language, TelegramMessage.COMMAND_START)),
            BotCommand("help", messages.text(language, TelegramMessage.COMMAND_HELP)),
            BotCommand("legal", messages.text(language, TelegramMessage.COMMAND_LEGAL)),
            BotCommand("donate", messages.text(language, TelegramMessage.COMMAND_DONATE)),
            BotCommand("language", messages.text(language, TelegramMessage.COMMAND_LANGUAGE)),
        )
    }

    private fun DeleteMyCommands.withScope(scope: BotCommandScope?): DeleteMyCommands {
        return scope?.let { scope(it) } ?: this
    }

    private fun SetMyCommands.withScope(scope: BotCommandScope?): SetMyCommands {
        return scope?.let { scope(it) } ?: this
    }

    private fun DeleteMyCommands.withLanguage(language: BotLanguage?): DeleteMyCommands {
        return language?.let { languageCode(it.code) } ?: this
    }

    private fun SetMyCommands.withLanguage(language: BotLanguage?): SetMyCommands {
        return language?.let { languageCode(it.code) } ?: this
    }

    private fun BotCommandScope?.name(): String {
        return this?.type ?: "default"
    }

    private companion object {
        private val commandLanguages = listOf(null) + BotLanguage.entries
    }
}
