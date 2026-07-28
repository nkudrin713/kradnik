package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.settings.DownloadMode
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

class TelegramSenderTest {
    private val bot: TelegramBot = mockk()
    private val modeView: TelegramModeView = mockk()
    private val downloadChoiceView: TelegramDownloadChoiceView = mockk()
    private val sender = TelegramSender(
        apiClient = TelegramApiClient(bot),
        modeView = modeView,
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
    fun sendsModeMenuAndReturnsMessageId() {
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Ask"))
        val request = slot<BaseRequest<*, *>>()
        every { modeView.text() } returns "mode"
        every { modeView.keyboard(DownloadMode.ASK) } returns keyboard
        every { bot.execute(capture(request)) } returns sendResponse(10)

        sender.sendModeMenu(100, DownloadMode.ASK) shouldBe 10

        (request.captured as SendMessage).getParameters()["reply_markup"] shouldBe keyboard
    }

    @Test
    fun sendsDownloadChoiceAsReplyToLink() {
        val keyboard = InlineKeyboardMarkup(InlineKeyboardButton("Video"))
        val request = slot<BaseRequest<*, *>>()
        every { downloadChoiceView.text() } returns "choice"
        every { downloadChoiceView.keyboard(400) } returns keyboard
        every { bot.execute(capture(request)) } returns sendResponse(10)

        sender.sendDownloadChoice(
            chatId = 100,
            replyToMessageId = 200,
            telegramUpdateId = 400,
        ) shouldBe 10

        val actual = request.captured as SendMessage
        actual.getParameters()["reply_markup"] shouldBe keyboard
        actual.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
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
}
