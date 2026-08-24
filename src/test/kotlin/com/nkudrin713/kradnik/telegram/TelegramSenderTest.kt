package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.DeleteMessage
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import java.util.UUID

class TelegramSenderTest {
    private val bot: TelegramBot = mockk()
    private val downloadChoiceView: TelegramDownloadChoiceView = mockk()
    private val sender = TelegramSender(
        apiClient = TelegramApiClient(bot),
        downloadChoiceView = downloadChoiceView,
    )

    @Test
    fun sendsMessage() {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(10)

        sender.sendMessage(100, "text")

        val actual = request.captured as SendMessage
        actual.getParameters()["chat_id"] shouldBe 100L
        actual.getParameters()["text"] shouldBe "text"
    }

    @Test
    fun sendsStatusAndReturnsMessageId() {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(10)

        sender.sendStatus(100, TelegramDownloadStatus.QUEUED) shouldBe 10

        val actual = request.captured as SendMessage
        actual.getParameters()["text"] shouldBe TelegramDownloadStatus.QUEUED.text
    }

    @Test
    fun skipsStatusEditWithoutMessageId() {
        sender.editStatus(100, null, TelegramDownloadStatus.ERROR)

        verify(exactly = 0) { bot.execute(any<BaseRequest<*, *>>()) }
    }

    @Test
    fun editsStatus() {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns okResponse()

        sender.editStatus(100, 10, TelegramDownloadStatus.ERROR)

        val actual = request.captured as EditMessageText
        actual.getParameters()["message_id"] shouldBe 10
        actual.getParameters()["text"] shouldBe TelegramDownloadStatus.ERROR.text
    }

    @Test
    fun editsDownloadChoice() {
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Video"))
        val request = slot<BaseRequest<*, *>>()
        val token = UUID.randomUUID()
        val options = listOf(option())
        val mediaInfo = DownloadChoiceMediaInfo("Channel", "Title", 120)
        every { downloadChoiceView.text(mediaInfo) } returns "choice"
        every { downloadChoiceView.keyboard(token, options) } returns keyboard
        every { bot.execute(capture(request)) } returns okResponse()

        sender.editDownloadChoice(
            chatId = 100,
            messageId = 200,
            sessionToken = token,
            mediaInfo = mediaInfo,
            options = options,
        )

        val actual = request.captured as EditMessageText
        actual.getParameters()["reply_markup"] shouldBe keyboard
        actual.getParameters()["message_id"] shouldBe 200
        actual.getParameters()["parse_mode"] shouldBe "HTML"
    }

    @Test
    fun answersCallbackWithToastAndAlert() {
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returns okResponse()

        sender.answerCallback(
            callbackQueryId = "callback",
            text = "selected",
            showAlert = true,
        )

        val actual = requests.single() as AnswerCallbackQuery
        actual.getParameters()["text"] shouldBe "selected"
        actual.getParameters()["show_alert"] shouldBe true
    }

    @Test
    fun deletesMessage() {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns okResponse()

        sender.deleteMessage(100, 10)

        request.captured as DeleteMessage
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

    private fun option(): DownloadChoiceOptionSnapshot {
        return DownloadChoiceOptionSnapshot(
            key = "video_720",
            label = "720p",
            sizeBytes = 1_000_000,
            approximateSize = false,
            available = true,
            unavailableReason = null,
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            cacheKey = "cache",
            outputType = OutputType.VIDEO,
            strategy = DownloadStrategy.YOUTUBE_YT_DLP,
            presetName = "video_720",
            formatSelector = "22",
            extraArgs = emptyList(),
        )
    }
}
