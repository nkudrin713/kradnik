package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.delivery.MessageAddress
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.SentGuestMessage
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.AnswerGuestQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.DeleteMessage
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import com.pengrad.telegrambot.response.SentGuestMessageResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test

class TelegramSenderTest {
    private val bot: TelegramBot = mockk()
    private val downloadChoiceView: TelegramDownloadChoiceView = mockk()
    private val downloadJobView: TelegramDownloadJobView = mockk()
    private val messages = telegramMessages()
    private val sender = TelegramSender(
        apiClient = TelegramApiClient(bot),
        downloadChoiceView = downloadChoiceView,
        downloadJobView = downloadJobView,
        messages = messages,
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
        actual.getParameters()["text"] shouldBe messages.text(BotLanguage.EN, MessageKey.STATUS_QUEUED)
    }

    @Test
    fun answersGuestQueryAndReturnsInlineAddress() {
        val request = slot<BaseRequest<*, *>>()
        val response = mockk<SentGuestMessageResponse> {
            every { isOk } returns true
            every { result } returns SentGuestMessage("inline-message")
        }
        every { bot.execute(capture(request)) } returns response

        sender.answerGuestMessage("guest-query", "Analyzing") shouldBe
            MessageAddress.Inline("inline-message")

        val actual = request.captured.shouldBeInstanceOf<AnswerGuestQuery>()
        actual.getParameters()["guest_query_id"] shouldBe "guest-query"
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
        actual.getParameters()["text"] shouldBe messages.text(BotLanguage.EN, MessageKey.STATUS_ERROR)
    }

    @Test
    fun failedStatusKeepsRetryButtonWhenQueueRefreshArrivesLate() {
        val request = slot<BaseRequest<*, *>>()
        val token = UUID.randomUUID()
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Retry last link"))
        every { bot.execute(capture(request)) } returns okResponse()
        every { downloadJobView.repeatKeyboard(token, BotLanguage.EN) } returns keyboard

        sender.editFailure(MessageAddress.Chat(100, 10), "Unavailable", token, BotLanguage.EN, 42)
        sender.editQueuedJob(MessageAddress.Chat(100, 10), 42, BotLanguage.EN, DownloadWorkloadType.SINGLE, 1) { false }

        verify(exactly = 1) { bot.execute(any<BaseRequest<*, *>>()) }
        request.captured.getParameters()["text"] shouldBe "Unavailable"
        request.captured.getParameters()["reply_markup"] shouldBe keyboard
    }

    @Test
    fun unchangedQueuePositionIsHarmless() {
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Refresh"))
        every { downloadJobView.queueKeyboard(42, BotLanguage.EN) } returns keyboard
        every { bot.execute(any<BaseRequest<*, *>>()) } returns mockk {
            every { isOk } returns false
            every { errorCode() } returns 400
            every { description() } returns "Bad Request: message is not modified"
        }

        sender.editQueuedJob(MessageAddress.Chat(100, 10), 42, BotLanguage.EN, DownloadWorkloadType.SINGLE, 1) { true }
    }

    @Test
    fun editsFinalStatusAsHtmlAndRemovesCancelKeyboard() {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns okResponse()

        sender.editFinalMessage(MessageAddress.Chat(100, 10), "1. <a href=\"https://example.com\">Track</a>", html = true)

        val actual = request.captured as EditMessageText
        actual.getParameters()["message_id"] shouldBe 10
        actual.getParameters()["parse_mode"] shouldBe "HTML"
        actual.getParameters()["reply_markup"] shouldBe InlineKeyboardMarkup()
    }

    @Test
    fun editsDownloadChoice() {
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Video"))
        val request = slot<BaseRequest<*, *>>()
        val token = UUID.randomUUID()
        val options = listOf(option())
        val mediaInfo = DownloadChoiceMediaInfo(title = "Title", durationSeconds = 120)
        every { downloadChoiceView.text(mediaInfo, BotLanguage.EN) } returns "choice"
        every { downloadChoiceView.keyboard(token, options, BotLanguage.EN) } returns keyboard
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
    fun editsInlineDownloadChoice() {
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Video"))
        val request = slot<BaseRequest<*, *>>()
        val token = UUID.randomUUID()
        val options = listOf(option())
        val mediaInfo = DownloadChoiceMediaInfo(title = "Title", durationSeconds = 120)
        every { downloadChoiceView.text(mediaInfo, BotLanguage.EN) } returns "choice"
        every { downloadChoiceView.keyboard(token, options, BotLanguage.EN) } returns keyboard
        every { bot.execute(capture(request)) } returns okResponse()

        sender.editDownloadChoice(
            address = MessageAddress.Inline("inline-message"),
            sessionToken = token,
            mediaInfo = mediaInfo,
            options = options,
        )

        val actual = request.captured.shouldBeInstanceOf<EditMessageText>()
        actual.getParameters()["inline_message_id"] shouldBe "inline-message"
        actual.getParameters()["reply_markup"] shouldBe keyboard
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
            spec = DownloadSpec(
                originalUrl = "https://example.com/video",
                normalizedUrl = "https://example.com/video",
                cacheKey = "cache",
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "video_720",
                formatSelector = "22",
            ),
        )
    }
}
