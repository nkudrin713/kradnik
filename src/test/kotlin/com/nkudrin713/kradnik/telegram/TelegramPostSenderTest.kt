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
import com.pengrad.telegrambot.request.richmessages.SendRichMessage
import com.pengrad.telegrambot.response.SendResponse
import com.pengrad.telegrambot.utility.BotUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramPostSenderTest {
    private val api: TelegramApiClient = mockk()
    private val probe: VideoMetadataProbe = mockk()
    private val request = slot<SendRichMessage>()
    private val mapper = jacksonObjectMapper()
    private fun sender(local: Boolean = false) = TelegramPostSender(api, probe, if (local) TelegramBotProperties(token = "test", apiUrl = "http://localhost:8081/bot", fileApiUrl = "http://localhost:8081/file/bot") else TelegramBotProperties(token = "test"))

    @Test
    fun uploadsNestedMixedMediaOnceWithLongLiteralCaption(@TempDir dir: Path) = runTest {
        val items = listOf(PostMedia(PostMediaKind.PHOTO, dir.resolve("image.jpg")), PostMedia(PostMediaKind.VIDEO, dir.resolve("video.mp4")))
        val caption = "<b>literal & text</b>\n😀" + "я".repeat(2200)
        coEvery { probe.probe(items[1].file) } returns VideoMetadata(720, 1280, null, null, durationSeconds = 8)
        coEvery { api.executeIo(capture(request), any()) } returns response(items.map { it.kind })

        val ids = sender().send(DeliveryContext(100, 200, postText = caption), items)

        assertEquals(items.map { it.kind }, ids.map { it.kind })
        assertEquals(listOf("file-0", "file-1"), ids.map { it.fileId })
        assertTrue(request.captured.isMultipart)
        assertEquals(2, request.captured.parameters.values.filterIsInstance<InputFile>().size)
        val post = json().path("blocks")[0]
        assertEquals("slideshow", post.path("type").asText())
        assertEquals(caption, post.path("caption").path("text").asText())
        assertEquals(listOf("photo", "video"), post.path("blocks").map { it.path("type").asText() })
        assertEquals(8, post.path("blocks")[1].path("video").path("duration").asInt())
        assertTrue(request.captured.parameters.containsKey("reply_parameters"))
        coVerify(exactly = 1) { api.executeIo(any<SendRichMessage>(), any()) }
    }

    @Test
    fun reusesTwentyCachedFilesWithoutUploadsOrCaption() = runTest {
        val items = List(20) { CachedPostItem(if (it % 2 == 0) PostMediaKind.VIDEO else PostMediaKind.PHOTO, "cached-$it") }
        coEvery { api.executeIo(capture(request), any()) } returns response(items.map { it.kind })
        sender().sendCached(DeliveryContext(100), items)
        assertFalse(request.captured.isMultipart)
        assertFalse(json().path("blocks")[0].has("caption"))
        assertEquals(20, json().path("blocks")[0].path("blocks").size())
        coVerify(exactly = 0) { probe.probe(any()) }
    }

    @Test
    fun usesSharedVolumeUriAndCaptionForOnePhoto(@TempDir dir: Path) = runTest {
        val file = dir.resolve("photo with spaces.jpg")
        coEvery { api.executeIo(capture(request), any()) } returns response(listOf(PostMediaKind.PHOTO))
        sender(local = true).send(DeliveryContext(100, postText = "caption"), listOf(PostMedia(PostMediaKind.PHOTO, file)))
        assertFalse(request.captured.isMultipart)
        val photo = json().path("blocks")[0]
        assertEquals("photo", photo.path("type").asText())
        assertEquals(file.toUri().toString(), photo.path("photo").path("media").asText())
        assertEquals("caption", photo.path("caption").path("text").asText())
    }

    @Test
    fun rejectsInlinePostAndIncompleteTelegramResponse() = runTest {
        val items = listOf(CachedPostItem(PostMediaKind.VIDEO, "id"))
        assertFailsWith<IllegalArgumentException> { sender().sendCached(DeliveryContext(100, inlineMessageId = "inline"), items) }
        coVerify(exactly = 0) { api.executeIo(any<SendRichMessage>(), any()) }
        coEvery { api.executeIo(capture(request), any()) } returns response(listOf(PostMediaKind.PHOTO))
        assertFailsWith<TelegramSendException> { sender().sendCached(DeliveryContext(100), items) }
    }

    private fun json() = mapper.readTree(BotUtils.toJson(request.captured.parameters.getValue("rich_message")))

    private fun response(kinds: List<PostMediaKind>): SendResponse {
        val blocks = kinds.mapIndexed { index, kind ->
            when (kind) {
                PostMediaKind.PHOTO -> """{"type":"photo","photo":[{"file_id":"file-$index","file_unique_id":"unique-$index","width":100,"height":100}]}"""
                PostMediaKind.VIDEO -> """{"type":"video","video":{"file_id":"file-$index","file_unique_id":"unique-$index","width":720,"height":1280,"duration":8}}"""
            }
        }
        val content = if (blocks.size == 1) blocks.single() else """{"type":"slideshow","blocks":[${blocks.joinToString(",")}]}"""
        return BotUtils.fromJson("""{"ok":true,"result":{"message_id":1,"rich_message":{"blocks":[$content]}}}""", SendResponse::class.java)
    }
}
