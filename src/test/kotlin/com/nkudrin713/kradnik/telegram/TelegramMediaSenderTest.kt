package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadata
import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Audio
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Video
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendAudio
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
        requestFactory = TelegramMediaRequestFactory(TelegramBotProperties(token = "test-token")),
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
        result shouldBe TelegramSendResult("video-id", 456)
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
        result shouldBe TelegramSendResult("video-id", 456)
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
        result shouldBe TelegramSendResult("audio-id", 456)
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
        result shouldBe TelegramSendResult("audio-id", 456)
    }

    private fun sendResponse(video: Video? = null, audio: Audio? = null): SendResponse {
        return mockk {
            every { isOk } returns true
            every { message() } returns mockk<Message> {
                every { video() } returns video
                every { audio() } returns audio
            }
        }
    }

    private fun video(fileId: String, fileSize: Long): Video {
        return Video(fileId, "unique", 1920, 1080, 60, null, emptyList(), null, null, null, fileSize)
    }

    private fun audio(fileId: String, fileSize: Long): Audio {
        return Audio(fileId, "unique", null, null, null, null, null, fileSize, null)
    }
}
