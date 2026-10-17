package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.BotCommand
import com.pengrad.telegrambot.model.botcommandscope.BotCommandsScopeChat
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.DeleteMyCommands
import com.pengrad.telegrambot.request.SetMyCommands
import com.pengrad.telegrambot.response.BaseResponse
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.ApplicationArguments
import kotlin.test.Test

class TelegramCommandsInitializerTest {
    private val bot: TelegramBot = mockk()
    private val args: ApplicationArguments = mockk()

    @Test
    fun selectedLanguageOverridesTelegramLanguageOnlyForTheSelectedPrivateChat() {
        val requests = mutableListOf<SetMyCommands>()
        every { bot.execute(capture(requests)) } returns okResponse()
        val messages = telegramMessages()
        val initializer = TelegramCommandsInitializer(bot, messages)
        val languages = listOf(BotLanguage.EN, BotLanguage.RU, BotLanguage.RU_INFORMAL, BotLanguage.EN)

        languages.forEach { initializer.setChatLanguage(100, it) }

        requests.size shouldBe languages.size
        requests.zip(languages).forEach { (request, language) ->
            val parameters = request.getParameters()
            (parameters["scope"] as BotCommandsScopeChat).chatId shouldBe 100L
            parameters["language_code"] shouldBe null
            val commands = (parameters["commands"] as Array<*>).filterIsInstance<BotCommand>()
            commands.map { it.command() } shouldBe listOf("start", "help", "legal", "donate", "language")
            commands.first().description() shouldBe messages.text(language, MessageKey.COMMAND_START)
            commands.last().description() shouldBe messages.text(language, MessageKey.COMMAND_LANGUAGE)
        }

        initializer.setChatLanguage(200, BotLanguage.RU)
        (requests.last().getParameters()["scope"] as BotCommandsScopeChat).chatId shouldBe 200L
    }

    @Test
    fun deletesCommandsBeforeSettingActualListForSupportedScopes() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returns okResponse()

        TelegramCommandsInitializer(bot, telegramMessages()).run(args)

        requests.size shouldBe 12
        requests.chunked(2).forEach { pair ->
            pair[0]::class shouldBe DeleteMyCommands::class
            pair[1]::class shouldBe SetMyCommands::class
        }
        requests[1].getParameters()["language_code"] shouldBe null
        requests[3].getParameters()["language_code"] shouldBe "en"
        requests[5].getParameters()["language_code"] shouldBe "ru"
        requests.map { it.getParameters()["language_code"] }.toSet() shouldBe setOf(null, "en", "ru")
    }

    private fun okResponse(): BaseResponse {
        return mockk {
            every { isOk } returns true
        }
    }
}
