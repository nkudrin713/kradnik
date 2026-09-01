package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.pengrad.telegrambot.TelegramBot
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
    }

    private fun okResponse(): BaseResponse {
        return mockk {
            every { isOk } returns true
        }
    }
}
