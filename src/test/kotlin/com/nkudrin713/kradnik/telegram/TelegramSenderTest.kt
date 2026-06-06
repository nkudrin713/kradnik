package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.download.domain.OutputType
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Audio
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Video
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.DeleteMessage
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.PinChatMessage
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendVideo
import com.pengrad.telegrambot.request.UnpinChatMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.string
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class TelegramSenderTest {
    private val bot: TelegramBot = mockk()
    private val modeView: TelegramModeView = mockk()
    private val videoMetadataProbe: VideoMetadataProbe = mockk()
    private val sender = TelegramSender(
        bot = bot,
        modeView = modeView,
        videoMetadataProbe = videoMetadataProbe,
    )

    @Test
    fun sendMessage() {
        val chatId = chatId()
        val text = text()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse()

        sender.sendMessage(chatId, text)

        val actual = request.captured as SendMessage
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["text"] shouldBe text
    }

    @Test
    fun sendStatus() {
        val chatId = chatId()
        val messageId = messageId()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(messageId)

        val actualMessageId = sender.sendStatus(chatId, TelegramDownloadStatus.QUEUED)

        val actual = request.captured as SendMessage
        actualMessageId shouldBe messageId
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["text"] shouldBe TelegramDownloadStatus.QUEUED.text
    }

    @Test
    fun editStatusSkipsNullMessageId() {
        val chatId = chatId()

        sender.editStatus(chatId, null, TelegramDownloadStatus.ERROR)

        verify(exactly = 0) { bot.execute(any<BaseRequest<*, *>>()) }
    }

    @Test
    fun editStatus() {
        val chatId = chatId()
        val messageId = messageId()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns okResponse()

        sender.editStatus(chatId, messageId, TelegramDownloadStatus.ERROR)

        val actual = request.captured as EditMessageText
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["message_id"] shouldBe messageId
        actual.getParameters()["text"] shouldBe TelegramDownloadStatus.ERROR.text
    }

    @Test
    fun sendModeMenu() {
        val chatId = chatId()
        val text = text()
        val keyboard = keyboard("Video")
        val request = slot<BaseRequest<*, *>>()
        every { modeView.text() } returns text
        every { modeView.keyboard(OutputType.VIDEO) } returns keyboard
        every { bot.execute(capture(request)) } returns sendResponse()

        sender.sendModeMenu(chatId, OutputType.VIDEO)

        val actual = request.captured as SendMessage
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["text"] shouldBe text
        actual.getParameters()["reply_markup"] shouldBe keyboard
    }

    @Test
    fun editModeMenu() {
        val chatId = chatId()
        val messageId = messageId()
        val text = text()
        val keyboard = keyboard("Audio")
        val request = slot<BaseRequest<*, *>>()
        every { modeView.text() } returns text
        every { modeView.keyboard(OutputType.AUDIO) } returns keyboard
        every { bot.execute(capture(request)) } returns okResponse()

        sender.editModeMenu(chatId, messageId, OutputType.AUDIO)

        val actual = request.captured as EditMessageText
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["message_id"] shouldBe messageId
        actual.getParameters()["text"] shouldBe text
        actual.getParameters()["reply_markup"] shouldBe keyboard
    }

    @Test
    fun answerCallback() {
        val callbackQueryId = text()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns okResponse()

        sender.answerCallback(callbackQueryId)

        val actual = request.captured as AnswerCallbackQuery
        actual.getParameters()["callback_query_id"] shouldBe callbackQueryId
    }

    @Test
    fun deleteMessage() {
        val chatId = chatId()
        val messageId = messageId()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns okResponse()

        sender.deleteMessage(chatId, messageId)

        val actual = request.captured as DeleteMessage
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["message_id"] shouldBe messageId
    }

    @Test
    fun sendCachedVideo() {
        val chatId = chatId()
        val fileId = text()
        val telegramFileId = text()
        val fileSize = fileSize()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(
            video = video(telegramFileId, fileSize),
        )

        val actualResult = sender.sendCachedVideo(chatId, fileId)

        val actual = request.captured as SendVideo
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["video"] shouldBe fileId
        actualResult.fileId shouldBe telegramFileId
        actualResult.fileSize shouldBe fileSize
    }

    @Test
    fun sendCachedAudio() {
        val chatId = chatId()
        val fileId = text()
        val telegramFileId = text()
        val fileSize = fileSize()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(
            audio = audio(telegramFileId, fileSize),
        )

        val actualResult = sender.sendCachedAudio(chatId, fileId)

        val actual = request.captured as SendAudio
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["audio"] shouldBe fileId
        actualResult.fileId shouldBe telegramFileId
        actualResult.fileSize shouldBe fileSize
    }

    @Test
    fun sendAudioPassesMetadata(@TempDir tempDir: Path) = runTest {
        val chatId = chatId()
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        val telegramFileId = text()
        val fileSize = fileSize()
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(
            audio = audio(telegramFileId, fileSize),
        )

        val actualResult = sender.sendAudio(
            chatId = chatId,
            file = file,
            title = "audio title",
            performer = "artist",
            durationSeconds = 120,
        )

        val actual = request.captured as SendAudio
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters()["title"] shouldBe "audio title"
        actual.getParameters()["performer"] shouldBe "artist"
        actual.getParameters()["duration"] shouldBe 120
        actualResult.fileId shouldBe telegramFileId
        actualResult.fileSize shouldBe fileSize
    }

    @Test
    fun sendDonationMessage() {
        val chatId = chatId()
        val donationUrl = "https://example.com/donate"
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse()

        sender.sendDonationMessage(chatId, donationUrl)

        val actual = request.captured as SendMessage
        actual.getParameters()["chat_id"] shouldBe chatId
        actual.getParameters().containsKey("text") shouldBe true
        actual.getParameters().containsKey("reply_markup") shouldBe true
    }

    @Test
    fun sendDonationPin() {
        val channelId = "@channel"
        val donationUrl = "https://example.com/donate"
        val messageId = messageId()
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returnsMany listOf(
            sendResponse(messageId),
            okResponse(),
        )

        val actualMessageId = sender.sendDonationPin(channelId, donationUrl)

        actualMessageId shouldBe messageId
        val sendMessage = requests[0] as SendMessage
        sendMessage.getParameters()["chat_id"] shouldBe channelId
        sendMessage.getParameters().containsKey("text") shouldBe true
        sendMessage.getParameters().containsKey("reply_markup") shouldBe true
        val pinMessage = requests[1] as PinChatMessage
        pinMessage.getParameters()["chat_id"] shouldBe channelId
        pinMessage.getParameters()["message_id"] shouldBe messageId
    }

    @Test
    fun updateDonationPin() {
        val channelId = "@channel"
        val donationUrl = "https://example.com/donate"
        val messageId = messageId()
        val requests = mutableListOf<BaseRequest<*, *>>()
        every { bot.execute(capture(requests)) } returnsMany listOf(
            okResponse(),
            okResponse(),
            okResponse(),
        )

        sender.updateDonationPin(channelId, messageId, donationUrl)

        val editMessage = requests[0] as EditMessageText
        editMessage.getParameters()["chat_id"] shouldBe channelId
        editMessage.getParameters()["message_id"] shouldBe messageId
        editMessage.getParameters().containsKey("text") shouldBe true
        editMessage.getParameters().containsKey("reply_markup") shouldBe true
        val unpinMessage = requests[1] as UnpinChatMessage
        unpinMessage.getParameters()["chat_id"] shouldBe channelId
        unpinMessage.getParameters()["message_id"] shouldBe messageId
        val pinMessage = requests[2] as PinChatMessage
        pinMessage.getParameters()["chat_id"] shouldBe channelId
        pinMessage.getParameters()["message_id"] shouldBe messageId
        pinMessage.getParameters()["disable_notification"] shouldBe true
    }

    private fun chatId(): Long {
        return Arb.long().next()
    }

    private fun messageId(): Int {
        return Arb.long(1..Int.MAX_VALUE.toLong()).next().toInt()
    }

    private fun fileSize(): Long {
        return Arb.long(1..Long.MAX_VALUE).next()
    }

    private fun text(): String {
        return Arb.string().next()
    }

    private fun keyboard(label: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(InlineKeyboardButton(label))
    }

    private fun okResponse(): BaseResponse {
        return mockk {
            every { isOk } returns true
        }
    }

    private fun sendResponse(
        messageId: Int = messageId(),
        video: Video? = null,
        audio: Audio? = null,
    ): SendResponse {
        val message = mockk<Message> {
            every { messageId() } returns messageId
            every { video() } returns video
            every { audio() } returns audio
        }

        return mockk {
            every { isOk } returns true
            every { message() } returns message
        }
    }

    private fun video(fileId: String, fileSize: Long): Video {
        return Video(
            fileId,
            "unique-$fileId",
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            VIDEO_DURATION_SECONDS,
            null,
            emptyList(),
            null,
            null,
            null,
            fileSize,
        )
    }

    private fun audio(fileId: String, fileSize: Long): Audio {
        return Audio(
            fileId,
            "unique-$fileId",
            null,
            null,
            null,
            null,
            null,
            fileSize,
            null,
        )
    }

    private companion object {
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_DURATION_SECONDS = 60
    }
}
