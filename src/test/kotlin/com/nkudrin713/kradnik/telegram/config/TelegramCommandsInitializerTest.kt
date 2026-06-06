package com.nkudrin713.kradnik.telegram.config

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
    fun deletesCommandsBeforeSettingActualList() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returns okResponse()

        TelegramCommandsInitializer(bot).run(args)

        requests[0]::class shouldBe DeleteMyCommands::class
        requests[1]::class shouldBe SetMyCommands::class
    }

    private fun okResponse(): BaseResponse {
        return mockk {
            every { isOk } returns true
        }
    }
}
