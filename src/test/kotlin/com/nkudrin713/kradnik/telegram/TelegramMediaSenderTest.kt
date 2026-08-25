package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadata
import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Audio
import com.pengrad.telegrambot.model.Document
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Video
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendDocument
import com.pengrad.telegrambot.request.SendVideo
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class TelegramMediaSenderTest {
    private val bot: TelegramBot = mockk()
    private val videoMetadataProbe: VideoMetadataProbe = mockk()
    private val sender = TelegramMediaSender(
        apiClient = TelegramApiClient(bot),
        videoMetadataProbe = videoMetadataProbe,
        properties = TelegramBotProperties(token = "test-token"),
    )

    @Test
    fun sendsVideoWithMetadata(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")
        val request = slot<BaseRequest<*, *>>()
        coEvery { videoMetadataProbe.probe(file) } returns VideoMetadata(1920, 1080, "1:1", "16:9")
        every { bot.execute(capture(request)) } returns sendResponse(video = video("video-id", 456))

        val result = sender.sendVideo(
            chatId = 100,
            file = file,
            replyToMessageId = 200,
        )

        val actual = request.captured as SendVideo
        actual.getParameters()["width"] shouldBe 1920
        actual.getParameters()["height"] shouldBe 1080
        actual.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
        result shouldBe "video-id"
    }

    @Test
    fun sendsCachedVideo() = runTest {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(video = video("video-id", 456))

        val result = sender.sendCachedVideo(
            chatId = 100,
            fileId = "cached-id",
            replyToMessageId = 200,
        )

        val actual = request.captured as SendVideo
        actual.getParameters()["video"] shouldBe "cached-id"
        actual.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
        result shouldBe "video-id"
    }

    @Test
    fun sendsAudioWithMetadata(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(audio = audio("audio-id", 456))

        val result = sender.sendAudio(
            chatId = 100,
            file = file,
            title = "title",
            performer = "artist",
            durationSeconds = 120,
            replyToMessageId = 200,
        )

        val actual = request.captured as SendAudio
        actual.getParameters()["title"] shouldBe "title"
        actual.getParameters()["performer"] shouldBe "artist"
        actual.getParameters()["duration"] shouldBe 120
        actual.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
        result shouldBe "audio-id"
    }

    @Test
    fun sendsCachedAudio() = runTest {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(audio = audio("audio-id", 456))

        val result = sender.sendCachedAudio(
            chatId = 100,
            fileId = "cached-id",
            replyToMessageId = 200,
        )

        val actual = request.captured as SendAudio
        actual.getParameters()["audio"] shouldBe "cached-id"
        actual.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
        result shouldBe "audio-id"
    }

    @Test
    fun sendsCoverAsDocument(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("cover.jpg")
        file.writeText("cover")
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(document = document("cover-id", 456))

        val result = sender.sendDocument(100, file, replyToMessageId = 200)

        (request.captured as SendDocument).getParameters()["reply_parameters"]
            .shouldBeInstanceOf<ReplyParameters>()
        result shouldBe "cover-id"
    }

    @Test
    fun sendsCachedCoverDocument() = runTest {
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(document = document("cover-id", 456))

        val result = sender.sendCachedDocument(100, "cached-id", replyToMessageId = 200)

        val actual = request.captured as SendDocument
        actual.getParameters()["document"] shouldBe "cached-id"
        result shouldBe "cover-id"
    }

    @Test
    fun sendsLocalFilesByUri(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("media file.mp4")
        file.writeText("video")
        val request = slot<BaseRequest<*, *>>()
        coEvery { videoMetadataProbe.probe(file) } returns VideoMetadata(1920, 1080, "1:1", "16:9")
        every { bot.execute(capture(request)) } returns sendResponse(video = video("video-id", 456))
        val localSender = TelegramMediaSender(
            apiClient = TelegramApiClient(bot),
            videoMetadataProbe = videoMetadataProbe,
            properties = TelegramBotProperties(
                token = "test-token",
                apiUrl = "http://telegram-bot-api:8081/bot",
                fileApiUrl = "http://telegram-bot-api:8081/file/bot",
            ),
        )

        localSender.sendVideo(chatId = 100, file = file)

        val actual = request.captured as SendVideo
        actual.isMultipart shouldBe false
        actual.getParameters()["video"] shouldBe file.toAbsolutePath().toUri().toString()
    }

    @Test
    fun sendsLocalAudioByUri(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("audio file.mp3")
        file.writeText("audio")
        val request = slot<BaseRequest<*, *>>()
        every { bot.execute(capture(request)) } returns sendResponse(audio = audio("audio-id", 456))
        val localSender = TelegramMediaSender(
            apiClient = TelegramApiClient(bot),
            videoMetadataProbe = videoMetadataProbe,
            properties = TelegramBotProperties(
                token = "test-token",
                apiUrl = "http://telegram-bot-api:8081/bot",
                fileApiUrl = "http://telegram-bot-api:8081/file/bot",
            ),
        )

        localSender.sendAudio(
            chatId = 100,
            file = file,
            title = null,
            performer = null,
            durationSeconds = null,
        )

        val actual = request.captured as SendAudio
        actual.isMultipart shouldBe false
        actual.getParameters()["audio"] shouldBe file.toAbsolutePath().toUri().toString()
    }

    private fun sendResponse(
        video: Video? = null,
        audio: Audio? = null,
        document: Document? = null,
    ): SendResponse {
        return mockk {
            every { isOk } returns true
            every { message() } returns mockk<Message> {
                every { video() } returns video
                every { audio() } returns audio
                every { document() } returns document
            }
        }
    }

    private fun video(fileId: String, fileSize: Long): Video {
        return Video(fileId, "unique", 1920, 1080, 60, null, emptyList(), null, null, null, fileSize)
    }

    private fun audio(fileId: String, fileSize: Long): Audio {
        return Audio(fileId, "unique", null, null, null, null, null, fileSize, null)
    }

    private fun document(fileId: String, fileSize: Long): Document {
        return mockk {
            every { fileId() } returns fileId
            every { fileSize() } returns fileSize
        }
    }
}
