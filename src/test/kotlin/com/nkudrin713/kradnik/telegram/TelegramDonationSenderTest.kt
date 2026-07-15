package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.PinChatMessage
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.UnpinChatMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

class TelegramDonationSenderTest {
    private val bot: TelegramBot = mockk()
    private val sender = TelegramDonationSender(TelegramApiClient(bot))

    @Test
    fun sendsDonationMessage() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returns sendResponse(10)

        sender.sendMessage(100, "https://example.com/donate")

        (requests.single() as SendMessage).getParameters().containsKey("reply_markup") shouldBe true
    }

    @Test
    fun createsAndPinsDonationMessage() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returnsMany listOf(sendResponse(10), okResponse())

        sender.sendPin("@channel", "https://example.com/donate") shouldBe 10

        requests[0] as SendMessage
        requests[1] as PinChatMessage
    }

    @Test
    fun updatesAndRepinsDonationMessage() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returnsMany listOf(okResponse(), okResponse(), okResponse())

        sender.updatePin("@channel", 10, "https://example.com/donate")

        requests[0] as EditMessageText
        requests[1] as UnpinChatMessage
        requests[2] as PinChatMessage
    }

    @Test
    fun repinsWhenDonationMessageIsUnchanged() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returnsMany listOf(
            failedResponse(400, "Bad Request: message is not modified"),
            okResponse(),
            okResponse(),
        )

        sender.updatePin("@channel", 10, "https://example.com/donate")

        requests[1] as UnpinChatMessage
        requests[2] as PinChatMessage
    }

    private fun okResponse(): BaseResponse {
        return mockk {
            every { isOk } returns true
        }
    }

    private fun sendResponse(messageId: Int): SendResponse {
        return mockk {
            every { isOk } returns true
            every { message() } returns mockk<Message> {
                every { messageId() } returns messageId
            }
        }
    }

    private fun failedResponse(errorCode: Int, description: String): BaseResponse {
        return mockk {
            every { isOk } returns false
            every { errorCode() } returns errorCode
            every { description() } returns description
        }
    }
}
