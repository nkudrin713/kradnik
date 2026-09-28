package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadata
import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.model.Audio
import com.pengrad.telegrambot.model.Document
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.PhotoSize
import com.pengrad.telegrambot.model.Video
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.EditMessageMedia
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendDocument
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendVideo
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.MessagesResponse
import com.pengrad.telegrambot.response.SendResponse
import com.pengrad.telegrambot.utility.BotUtils
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class TelegramMediaSenderTest {
    private val apiClient: TelegramApiClient = mockk()
    private val videoMetadataProbe: VideoMetadataProbe = mockk()
    private val sender = TelegramMediaSender(
        apiClient = apiClient,
        videoMetadataProbe = videoMetadataProbe,
        properties = TelegramBotProperties(token = "test-token"),
    )

    @Test
    fun sendsVideoWithMetadata(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")
        val request = slot<SendVideo>()
        coEvery { videoMetadataProbe.probe(file) } returns VideoMetadata(1920, 1080, "1:1", "16:9")
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(video = video("video-id", 456))

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
        val request = slot<SendVideo>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(video = video("video-id", 456))

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
    fun editsInlineVideoByFileId() = runTest {
        val request = slot<EditMessageMedia>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns okResponse()

        sender.editInlineVideo("inline-message", "video-id") shouldBe "video-id"

        val actual = request.captured.shouldBeInstanceOf<EditMessageMedia>()
        actual.getParameters()["inline_message_id"] shouldBe "inline-message"
    }

    @Test
    fun sendsAudioWithMetadata(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        val request = slot<SendAudio>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(audio = audio("audio-id", 456))

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
        val request = slot<SendAudio>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(audio = audio("audio-id", 456))

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
    fun sendsOneHundredCachedAudiosInTenOrderedAlbums() = runTest {
        val requests = mutableListOf<SendMediaGroup>()
        coEvery { apiClient.executeIo(capture(requests), any()) } returnsMany
            (0 until 10).map { audioGroupResponse(it * 10 + 1..it * 10 + 10) }
        val result = sender.sendCachedAudios(
            chatId = 100,
            audios = (1..100).map { TelegramAudio("cached-$it", "Episode $it", "Artist", it * 60) },
            replyToMessageId = 200,
        )
        requests.size shouldBe 10
        result shouldBe (1..100).map { "audio-$it" }
        requests[0].parameters["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
        for ((index, request) in requests.withIndex()) {
            if (index > 0) request.parameters.containsKey("reply_parameters") shouldBe false
            request.isMultipart shouldBe false
            val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(BotUtils.toJson(request.parameters["media"]))
            json.size() shouldBe 10
            json.map { it.path("media").asText() } shouldBe (index * 10 + 1..index * 10 + 10).map { "cached-$it" }
            val first = json[0]
            first.path("title").asText() shouldBe "Episode ${index * 10 + 1}"
            first.path("duration").asInt() shouldBe (index * 10 + 1) * 60
            first.path("performer").asText() shouldBe "Artist"
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = [1, 2, 10, 11, 20, 21])
    fun sendsSingletonsOutsideAlbumsWithMetadataAndOnlyOneReply(count: Int) = runTest {
        val groupRequests = mutableListOf<SendMediaGroup>()
        val audioRequests = mutableListOf<SendAudio>()
        var returnedCount = 0
        coEvery { apiClient.executeIo(capture(groupRequests), any()) } answers {
            val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(BotUtils.toJson(firstArg<SendMediaGroup>().parameters["media"]))
            kotlin.test.assertTrue(json.size() in 2..10)
            audioGroupResponse(returnedCount + 1..returnedCount + json.size()).also { returnedCount += json.size() }
        }
        coEvery { apiClient.executeIo(capture(audioRequests), any()) } answers {
            returnedCount++
            sendResponse(audio = audio("audio-$returnedCount", 100))
        }
        sender.sendCachedAudios(100, (1..count).map { TelegramAudio("id-$it", "Track $it", "Artist", 60) }, 200) shouldBe
            (1..count).map { "audio-$it" }
        groupRequests.size shouldBe count / 10 + if (count % 10 >= 2) 1 else 0
        audioRequests.size shouldBe if (count % 10 == 1) 1 else 0
        if (audioRequests.isNotEmpty()) {
            val request = audioRequests.single()
            request.parameters["audio"] shouldBe "id-$count"
            request.parameters["title"] shouldBe "Track $count"
            request.parameters["performer"] shouldBe "Artist"
            request.parameters["duration"] shouldBe 60
            request.parameters.containsKey("reply_parameters") shouldBe (count == 1)
        }
    }

    @Test
    fun stopsSendingAfterAnIncompleteAlbumResponse() = runTest {
        coEvery { apiClient.executeIo(any<SendMediaGroup>(), any()) } returns audioGroupResponse(1..9)
        kotlin.test.assertFailsWith<TelegramSendException> {
            sender.sendCachedAudios(100, (1..20).map { TelegramAudio("id-$it", "Track $it") })
        }
        coVerify(exactly = 1) { apiClient.executeIo(any<SendMediaGroup>(), any()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationBetweenAlbumsPreventsTheNextRequest() = runTest {
        coEvery { apiClient.executeIo(any<SendMediaGroup>(), any()) } returns audioGroupResponse(1..10)
        val job = launch { sender.sendCachedAudios(100, (1..20).map { TelegramAudio("id-$it", "Track $it") }) }
        runCurrent()
        job.cancelAndJoin()
        coVerify(exactly = 1) { apiClient.executeIo(any<SendMediaGroup>(), any()) }
    }

    private fun audioGroupResponse(range: IntRange): MessagesResponse {
        val messages = range.joinToString(",") { """{"message_id":$it,"audio":{"file_id":"audio-$it","file_unique_id":"unique-$it","duration":60}}""" }
        return BotUtils.fromJson("""{"ok":true,"result":[$messages]}""", MessagesResponse::class.java)
    }

    @Test
    fun editsInlineAudioByFileId() = runTest {
        val request = slot<EditMessageMedia>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns okResponse()

        sender.editInlineAudio("inline-message", "audio-id", "title", "artist", 120) shouldBe "audio-id"

        val actual = request.captured.shouldBeInstanceOf<EditMessageMedia>()
        actual.getParameters()["inline_message_id"] shouldBe "inline-message"
    }

    @Test
    fun sendsCoverAsDocument(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("cover.jpg")
        file.writeText("cover")
        val request = slot<SendDocument>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(document = document("cover-id", 456))

        val result = sender.sendDocument(100, file, replyToMessageId = 200)

        (request.captured as SendDocument).getParameters()["reply_parameters"]
            .shouldBeInstanceOf<ReplyParameters>()
        result shouldBe "cover-id"
    }

    @Test
    fun sendsCachedCoverDocument() = runTest {
        val request = slot<SendDocument>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(document = document("cover-id", 456))

        val result = sender.sendCachedDocument(100, "cached-id", replyToMessageId = 200)

        val actual = request.captured as SendDocument
        actual.getParameters()["document"] shouldBe "cached-id"
        result shouldBe "cover-id"
    }

    @Test
    fun sendsPhotosAsMediaGroup(@TempDir tempDir: Path) = runTest {
        val first = tempDir.resolve("01.jpg").also { it.writeText("first") }
        val second = tempDir.resolve("02.jpg").also { it.writeText("second") }
        val request = slot<SendMediaGroup>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns messagesResponse("photo-1", "photo-2")

        val result = sender.sendPhotos(100, listOf(first, second), replyToMessageId = 200)

        request.captured.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
        result shouldBe listOf("photo-1", "photo-2")
    }

    @Test
    fun sendsEscapedPostTextAsMonospace() = runTest {
        val request = slot<SendMessage>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse()

        sender.sendMonospaceText(100, "Post <text> & more", replyToMessageId = 200)

        request.captured.getParameters()["text"] shouldBe "<code>Post &lt;text&gt; &amp; more</code>"
        request.captured.getParameters()["parse_mode"] shouldBe ParseMode.HTML
        request.captured.getParameters()["reply_parameters"].shouldBeInstanceOf<ReplyParameters>()
    }

    @Test
    fun editsInlineDocumentByFileId() = runTest {
        val request = slot<EditMessageMedia>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns okResponse()

        sender.editInlineDocument("inline-message", "document-id") shouldBe "document-id"

        val actual = request.captured.shouldBeInstanceOf<EditMessageMedia>()
        actual.getParameters()["inline_message_id"] shouldBe "inline-message"
    }

    @Test
    fun sendsLocalFilesByUri(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("media file.mp4")
        file.writeText("video")
        val request = slot<SendVideo>()
        coEvery { videoMetadataProbe.probe(file) } returns VideoMetadata(1920, 1080, "1:1", "16:9")
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(video = video("video-id", 456))
        val localSender = TelegramMediaSender(
            apiClient = apiClient,
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
        val request = slot<SendAudio>()
        coEvery { apiClient.executeIo(capture(request), any()) } returns sendResponse(audio = audio("audio-id", 456))
        val localSender = TelegramMediaSender(
            apiClient = apiClient,
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

    private fun okResponse(): BaseResponse {
        return mockk {
            every { isOk } returns true
        }
    }

    private fun messagesResponse(vararg fileIds: String): MessagesResponse {
        return mockk {
            every { isOk } returns true
            every { messages() } returns fileIds.map { fileId ->
                mockk<Message> {
                    every { photo() } returns arrayOf(
                        mockk<PhotoSize> {
                            every { fileId() } returns fileId
                        },
                    )
                }
            }.toTypedArray()
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
