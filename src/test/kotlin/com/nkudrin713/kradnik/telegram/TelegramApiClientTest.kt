package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.ResponseParameters
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TelegramApiClientTest {
    private val bot: TelegramBot = mockk()
    private val client = TelegramApiClient(bot)

    @Test
    fun returnsSuccessfulResponse() {
        val response: SendResponse = mockk {
            every { isOk } returns true
        }
        every { bot.execute(any<SendMessage>()) } returns response

        client.execute(SendMessage(100, "text")) shouldBe response
    }

    @Test
    fun classifiesRateLimitAsRetryable() = runTest {
        val parameters: ResponseParameters = mockk {
            every { retryAfter() } returns 17
        }
        every { bot.execute(any<SendMessage>()) } returns failedResponse(
            errorCode = 429,
            description = "Too Many Requests",
            parameters = parameters,
        )

        val error = assertFailsWith<TelegramSendException> {
            client.executeIo(SendMessage(100, "text"))
        }

        error.kind shouldBe TelegramSendFailureKind.RETRYABLE
        error.retryAfter shouldBe Duration.ofSeconds(17)
    }

    @Test
    fun classifiesInvalidFileId() {
        every { bot.execute(any<SendMessage>()) } returns failedResponse(400, "Bad Request: wrong file identifier")

        val error = assertFailsWith<TelegramSendException> {
            client.execute(SendMessage(100, "text"))
        }

        error.kind shouldBe TelegramSendFailureKind.INVALID_CACHED_FILE
    }

    @Test
    fun classifiesPermanentFailureAsTerminal() {
        every { bot.execute(any<SendMessage>()) } returns failedResponse(403, "Forbidden")

        val error = assertFailsWith<TelegramSendException> {
            client.execute(SendMessage(100, "text"))
        }

        error.kind shouldBe TelegramSendFailureKind.TERMINAL
        error.message shouldBe "Telegram send failed: code=403, description=Forbidden"
    }

    @Test
    fun classifiesMalformedSuccessfulResponseAsRetryable() {
        TelegramSendException("Telegram response does not contain message").kind shouldBe TelegramSendFailureKind.RETRYABLE
    }

    private fun failedResponse(
        errorCode: Int,
        description: String,
        parameters: ResponseParameters? = null,
    ): SendResponse {
        return mockk {
            every { isOk } returns false
            every { errorCode() } returns errorCode
            every { description() } returns description
            every { parameters() } returns parameters
        }
    }
}
