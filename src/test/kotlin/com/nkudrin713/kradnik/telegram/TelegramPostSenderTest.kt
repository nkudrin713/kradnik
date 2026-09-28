package com.nkudrin713.kradnik.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.PostMedia
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.telegram.CachedPostItem
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.video.VideoMetadata
import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.model.request.InputFile
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendVideo
import com.pengrad.telegrambot.response.MessagesResponse
import com.pengrad.telegrambot.response.SendResponse
import com.pengrad.telegrambot.utility.BotUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramPostSenderTest {
    private val api: TelegramApiClient = mockk()
    private val probe: VideoMetadataProbe = mockk()
    private val mapper = jacksonObjectMapper()
    private fun sender(local: Boolean = false) = TelegramPostSender(api, probe, if (local) TelegramBotProperties(token = "test", apiUrl = "http://localhost:8081/bot", fileApiUrl = "http://localhost:8081/file/bot") else TelegramBotProperties(token = "test"))

    @Test
    fun uploadsMixedAlbumInSourceOrderWithLiteralCaption(@TempDir dir: Path) = runTest {
        val items = listOf(PostMedia(PostMediaKind.PHOTO, dir.resolve("image.jpg")), PostMedia(PostMediaKind.VIDEO, dir.resolve("video.mp4")), PostMedia(PostMediaKind.PHOTO, dir.resolve("last.jpg")))
        val caption = "<b>literal & text</b>\n😀"
        val request = slot<SendMediaGroup>()
        coEvery { probe.probe(items[1].file) } returns VideoMetadata(720, 1280, null, null, durationSeconds = 8)
        coEvery { api.executeIo(capture(request), any()) } returns groupResponse(items.map { it.kind })

        val ids = sender().send(DeliveryContext(100, 200, postText = caption), items)

        assertEquals(items.map { it.kind }, ids.map { it.kind })
        assertEquals(listOf("file-0", "file-1", "file-2"), ids.map { it.fileId })
        assertTrue(request.captured.isMultipart)
        assertEquals(3, request.captured.parameters.values.filterIsInstance<InputFile>().size)
        val media = media(request.captured)
        assertEquals(listOf("photo", "video", "photo"), media.map { it.path("type").asText() })
        assertEquals(caption, media[0].path("caption").asText())
        assertFalse(media[1].has("caption"))
        assertFalse(media[0].has("parse_mode"))
        assertEquals(8, media[1].path("duration").asInt())
        assertEquals(720, media[1].path("width").asInt())
        assertTrue(media[1].path("supports_streaming").asBoolean())
        assertTrue(request.captured.parameters.containsKey("reply_parameters"))
        assertFalse(request.captured.parameters.containsKey("protect_content"))
        coVerify(exactly = 0) { api.executeIo(ofType<SendMessage>(), any()) }
    }

    @ParameterizedTest
    @ValueSource(ints = [11, 20])
    fun splitsCachedMixedPostsIntoNumberedAlbumsWithoutLosingOrder(count: Int) = runTest {
        val items = List(count) { CachedPostItem(if (it % 2 == 0) PostMediaKind.VIDEO else PostMediaKind.PHOTO, "cached-$it") }
        val requests = mutableListOf<SendMediaGroup>()
        var offset = 0
        coEvery { api.executeIo(capture(requests), any()) } answers {
            val size = media(firstArg()).size()
            groupResponse(items.drop(offset).take(size).map { it.kind }, offset).also { offset += size }
        }
        val result = sender().sendCached(DeliveryContext(100, 200, postText = "caption"), items)
        assertEquals(if (count == 11) listOf(9, 2) else listOf(10, 10), requests.map { media(it).size() })
        assertEquals(items.map { it.fileId }, requests.flatMap { media(it).map { value -> value.path("media").asText() } })
        assertEquals(items.map { it.kind }, result.map { it.kind })
        assertEquals(List(count) { "file-$it" }, result.map { it.fileId })
        assertEquals("caption\n\n1/2", media(requests.first())[0].path("caption").asText())
        assertEquals("2/2", media(requests.last())[0].path("caption").asText())
        assertTrue(requests.all { request -> media(request).drop(1).all { !it.has("caption") } })
        assertTrue(requests.none { it.isMultipart })
        assertFalse(requests.last().parameters.containsKey("reply_parameters"))
        coVerify(exactly = 0) { probe.probe(any()) }
    }

    @Test
    fun keepsAlbumNumbersWhenPostTextNeedsSeparateMessage() = runTest {
        val items = List(11) { CachedPostItem(PostMediaKind.PHOTO, "cached-$it") }
        val requests = mutableListOf<SendMediaGroup>()
        val texts = mutableListOf<SendMessage>()
        val text = "x".repeat(1024)
        var offset = 0
        coEvery { api.executeIo(capture(requests), any()) } answers {
            val size = media(firstArg()).size()
            groupResponse(items.drop(offset).take(size).map { it.kind }, offset).also { offset += size }
        }
        coEvery { api.executeIo(capture(texts), any()) } returns BotUtils.fromJson("""{"ok":true,"result":{"message_id":99}}""", SendResponse::class.java)

        sender().sendCached(DeliveryContext(100, postText = text), items)

        assertEquals("1/2", media(requests.first())[0].path("caption").asText())
        assertEquals("2/2", media(requests.last())[0].path("caption").asText())
        assertEquals(listOf(text), texts.map { it.parameters["text"] })
    }

    @Test
    fun usesSharedVolumeUriAndCaptionForSinglePhoto(@TempDir dir: Path) = runTest {
        val file = dir.resolve("photo with spaces.jpg")
        val request = slot<SendPhoto>()
        coEvery { api.executeIo(capture(request), any()) } returns singleResponse(PostMediaKind.PHOTO)
        sender(local = true).send(DeliveryContext(100, postText = "caption"), listOf(PostMedia(PostMediaKind.PHOTO, file)))
        assertFalse(request.captured.isMultipart)
        assertEquals(file.toUri().toString(), request.captured.parameters["photo"])
        assertEquals("caption", request.captured.caption)
    }

    @Test
    fun sendsSingleCachedVideoWithCaptionAndReply() = runTest {
        val request = slot<SendVideo>()
        coEvery { api.executeIo(capture(request), any()) } returns singleResponse(PostMediaKind.VIDEO)
        val result = sender().sendCached(DeliveryContext(100, 200, postText = "caption"), listOf(CachedPostItem(PostMediaKind.VIDEO, "cached")))
        assertEquals(listOf(CachedPostItem(PostMediaKind.VIDEO, "file-0")), result)
        assertFalse(request.captured.isMultipart)
        assertEquals("cached", request.captured.parameters["video"])
        assertEquals("caption", request.captured.caption)
        assertEquals(true, request.captured.supportsStreaming)
        assertTrue(request.captured.parameters.containsKey("reply_parameters"))
    }

    @Test
    fun sendsEntireLongTextSeparatelyAndPreservesEmojiAtChunkBoundary() = runTest {
        val album = slot<SendMediaGroup>()
        val texts = mutableListOf<SendMessage>()
        val kinds = listOf(PostMediaKind.PHOTO, PostMediaKind.VIDEO)
        val text = "я".repeat(4095) + "😀<b>literal</b>" + "z".repeat(1100)
        coEvery { api.executeIo(capture(album), any()) } returns groupResponse(kinds)
        coEvery { api.executeIo(capture(texts), any()) } returns BotUtils.fromJson("""{"ok":true,"result":{"message_id":99}}""", SendResponse::class.java)
        sender().sendCached(DeliveryContext(100, postText = text), kinds.map { CachedPostItem(it, "cached-$it") })
        assertTrue(media(album.captured).all { !it.has("caption") })
        assertEquals(text, texts.joinToString("") { it.parameters["text"] as String })
        assertEquals(2, texts.size)
        assertTrue(texts.all { (it.parameters["text"] as String).length <= 4096 })
        assertTrue((texts[1].parameters["text"] as String).startsWith("😀"))
        assertTrue(texts.all { !it.parameters.containsKey("parse_mode") })
        assertEquals(1, mapper.readTree(BotUtils.toJson(texts.first().parameters["reply_parameters"])).path("message_id").asInt())
    }

    @ParameterizedTest
    @ValueSource(ints = [1024, 1025])
    fun respectsCaptionBoundaryWithoutTruncatingText(length: Int) = runTest {
        val photo = slot<SendPhoto>()
        val text = "x".repeat(length)
        coEvery { api.executeIo(capture(photo), any()) } returns singleResponse(PostMediaKind.PHOTO)
        coEvery { api.executeIo(ofType<SendMessage>(), any()) } returns singleResponse(PostMediaKind.PHOTO)
        sender().sendCached(DeliveryContext(100, postText = text), listOf(CachedPostItem(PostMediaKind.PHOTO, "cached")))
        assertEquals(text.takeIf { length <= 1024 }, photo.captured.caption)
        coVerify(exactly = if (length > 1024) 1 else 0) { api.executeIo(match<SendMessage> { it.parameters["text"] == text }, any()) }
    }

    @Test
    fun rejectsInlinePostAndInvalidItemCountsBeforeSending() = runTest {
        val item = CachedPostItem(PostMediaKind.VIDEO, "id")
        assertFailsWith<IllegalArgumentException> { sender().sendCached(DeliveryContext(100, inlineMessageId = "inline"), listOf(item)) }
        for (count in listOf(0, 21)) {
            assertFailsWith<IllegalArgumentException> { sender().sendCached(DeliveryContext(100), List(count) { item }) }
        }
        coVerify(exactly = 0) { api.executeIo(ofType<SendMediaGroup>(), any()) }
        coVerify(exactly = 0) { api.executeIo(ofType<SendVideo>(), any()) }
    }

    @Test
    fun rejectsMissingOrMismatchedReturnedMedia() = runTest {
        val kinds = listOf(PostMediaKind.PHOTO, PostMediaKind.VIDEO)
        val items = kinds.map { CachedPostItem(it, "id") }
        coEvery { api.executeIo(ofType<SendMediaGroup>(), any()) } returns groupResponse(listOf(PostMediaKind.PHOTO))
        assertFailsWith<TelegramSendException> { sender().sendCached(DeliveryContext(100), items) }
        coEvery { api.executeIo(ofType<SendMediaGroup>(), any()) } returns groupResponse(kinds.reversed())
        assertFailsWith<TelegramSendException> { sender().sendCached(DeliveryContext(100), items) }
    }

    private fun media(request: SendMediaGroup) = mapper.readTree(BotUtils.toJson(request.parameters.getValue("media")))

    private fun message(kind: PostMediaKind, index: Int): String {
        val content = when (kind) {
            PostMediaKind.PHOTO -> """"photo":[{"file_id":"file-$index","file_unique_id":"unique-$index","width":100,"height":100}]"""
            PostMediaKind.VIDEO -> """"video":{"file_id":"file-$index","file_unique_id":"unique-$index","width":720,"height":1280,"duration":8}"""
        }
        return """{"message_id":${index + 1},$content}"""
    }

    private fun groupResponse(kinds: List<PostMediaKind>, offset: Int = 0): MessagesResponse = BotUtils.fromJson(
        """{"ok":true,"result":[${kinds.mapIndexed { index, kind -> message(kind, offset + index) }.joinToString(",") }]}""",
        MessagesResponse::class.java,
    )

    private fun singleResponse(kind: PostMediaKind): SendResponse = BotUtils.fromJson("""{"ok":true,"result":${message(kind, 0)}}""", SendResponse::class.java)
}
