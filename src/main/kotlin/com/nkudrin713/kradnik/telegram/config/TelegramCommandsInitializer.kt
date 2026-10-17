package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.BotCommand
import com.pengrad.telegrambot.model.botcommandscope.BotCommandScope
import com.pengrad.telegrambot.model.botcommandscope.BotCommandScopeAllPrivateChats
import com.pengrad.telegrambot.model.botcommandscope.BotCommandsScopeChat
import com.pengrad.telegrambot.request.DeleteMyCommands
import com.pengrad.telegrambot.request.SetMyCommands
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class TelegramCommandsInitializer(
    private val bot: TelegramBot,
    private val messages: Messages,
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

    fun setChatLanguage(chatId: Long, language: BotLanguage) {
        setCommands(BotCommandsScopeChat(chatId), language = null, commandLanguage = language)
    }

    private fun setCommands(scope: BotCommandScope?, language: BotLanguage?, commandLanguage: BotLanguage = language ?: BotLanguage.EN) {
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
            BotCommand("start", messages.text(language, MessageKey.COMMAND_START)),
            BotCommand("help", messages.text(language, MessageKey.COMMAND_HELP)),
            BotCommand("legal", messages.text(language, MessageKey.COMMAND_LEGAL)),
            BotCommand("donate", messages.text(language, MessageKey.COMMAND_DONATE)),
            BotCommand("language", messages.text(language, MessageKey.COMMAND_LANGUAGE)),
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
        // Telegram command language codes cannot represent our custom conversational style.
        private val commandLanguages = listOf(null, BotLanguage.EN, BotLanguage.RU)
    }
}
