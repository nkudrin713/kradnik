package com.nkudrin713.kradnik.download.vk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.http.HttpMediaDownloader
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.progress.JobProgress
import com.nkudrin713.kradnik.download.single.PostHandler
import com.nkudrin713.kradnik.download.testPlatformResolver
import com.nkudrin713.kradnik.ytdlp.YtDlpPreparedSource
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VkSourceAdapterTest {
    private val client = mockk<VkPostClient>()
    private val media = mockk<HttpMediaDownloader>()
    private val ytDlp = mockk<YtDlpService>()
    private val adapter = VkSourceAdapter(client, media, ytDlp)
    private val mapper = jacksonObjectMapper()
    private val source = DownloadRequestMapper.source(testPlatformResolver().resolve("https://vk.ru/wall-1_2").video)
    private val photo = """{"type":"photo","photo":{"sizes":[{"url":"https://sun9-1.userapi.com/small.jpg","width":100,"height":100},{"url":"https://sun9-1.userapi.com/full.jpg","width":1200,"height":800}]}}"""
    private val video = """{"type":"video","video":{"owner_id":-7,"id":9,"access_key":"abc123"}}"""

    @Test
    fun commonPostHandlerUsesFreshTextForVkTextOnlyResults(@TempDir directory: Path) = runTest {
        coEvery { client.fetch("-1_2") } returns mapper.readTree("""{"text":"Fresh post text","attachments":[]}""")
        val limits = TelegramUploadLimits(1000)
        val handler = PostHandler(
            DownloadEngine(PlatformResolver(listOf(adapter))),
            DownloadPreflightService(AudioUploadPlanner(limits), limits),
            mockk(),
        )
        val artifact = handler.produce(SingleMediaRequest(source, OutputType.POST, "key", "Stale menu text"), directory, 1, JobProgress {})
        assertEquals(MediaArtifact.Post(emptyList(), "Fresh post text"), artifact)
        coVerify(exactly = 0) { media.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { ytDlp.download(any(), any()) }
    }

    @Test
    fun preservesMixedOrderDuplicatesAndOnlySelectedAttachmentTypes(@TempDir directory: Path) = runTest {
        coEvery { client.fetch("-1_2") } returns mapper.readTree("""{"text":"A &amp; B<br>second line","attachments":[$photo,{"type":"audio"},$video,{"type":"doc"},$photo]}""")
        val post = adapter.prepare(source, true) as VkPreparedSource
        assertEquals("A & B\nsecond line", post.post.text)
        assertEquals(listOf(PostMediaKind.PHOTO, PostMediaKind.VIDEO, PostMediaKind.PHOTO), post.mediaKinds)
        assertEquals(post.items[0], post.items[2])
        assertEquals("https://vk.com/wall-1_2", post.canonicalUrl)
        val result = DownloadedFile(directory.resolve("photo.jpg"), 100)
        coEvery { media.download(any(), any(), any(), any(), any()) } returns result
        assertEquals(result, adapter.downloadPostItem(source, post, 0, directory))
        coVerify { media.download(URI("https://sun9-1.userapi.com/full.jpg"), directory.resolve("photo.jpg"), "image/", 10_000_000L, Duration.ofMinutes(10)) }
        coEvery { ytDlp.download(any(), directory) } returns result
        adapter.downloadPostItem(source, post, 1, directory)
        val videoSource = DownloadRequestMapper.source(testPlatformResolver().resolve("https://vk.com/video-7_9?list=abc123").video)
        coVerify { ytDlp.download(videoSource, directory) }
    }

    @Test
    fun convertsVkLinksToFullUrlsAndPreservesDescriptiveLabels() = runTest {
        val url = "https://disk.yandex.ru/d/3S2lzjyhiRmT7Q"
        val examples = mapOf(
            "👉[#alias|disk.yandex.ru/d/3S2lzjyh...|$url]" to "👉$url",
            "[#alias|https://disk.yandex.ru/d/3S2lzjyh…|$url]" to url,
            "[#alias|$url|$url]" to url,
            "[#alias|disk.yandex.ru/d/3S2lzjyhiRmT7Q|$url]" to url,
            "[#alias||$url]" to url,
            "[#alias|Скачать фотографии|$url]" to "Скачать фотографии: $url",
            "[$url|Скачать фотографии]" to "Скачать фотографии: $url",
            "[$url|disk.yandex.ru/d/3S2lzjyh...]" to url,
            "[#alias|disk|$url]" to "disk: $url",
            "[#alias|Другой адрес...|$url]" to "Другой адрес...: $url",
        )
        for ((text, expected) in examples) {
            coEvery { client.fetch(any()) } returns mapper.createObjectNode().put("text", text)
            assertEquals(expected, (adapter.prepare(source, true) as VkPreparedSource).post.text, text)
        }
    }

    @Test
    fun convertsMultipleLinksAlongsideHtmlAndPlainUrls() = runTest {
        val text = "<b>Фотографии</b><br>[#alias|Альбом|https://example.org/photos?a=1&amp;b=2]\n[https://example.org/video|Видео]\nhttps://example.org/plain"
        coEvery { client.fetch(any()) } returns mapper.createObjectNode().put("text", text)
        assertEquals(
            "Фотографии\nАльбом: https://example.org/photos?a=1&b=2\nВидео: https://example.org/video\nhttps://example.org/plain",
            (adapter.prepare(source, true) as VkPreparedSource).post.text,
        )
    }

    @Test
    fun preservesUnrecognizedAndMalformedLinkMarkup() = runTest {
        val text = "[id123|Автор] [#unknown|Подпись|https://example.org] [#alias|Нет адреса] [#alias|Адрес|https://] [#alias|Адрес|https://example.org|лишнее] [#alias|Файл|ftp://example.org/file]"
        coEvery { client.fetch(any()) } returns mapper.createObjectNode().put("text", text)
        assertEquals(text, (adapter.prepare(source, true) as VkPreparedSource).post.text)
    }

    @Test
    fun includesRepostedTextAndMediaWithoutFollowingLinks() = runTest {
        coEvery { client.fetch(any()) } returns mapper.readTree("""{"text":"Comment","attachments":[{"type":"link","link":{"url":"https://example.org"}}],"copy_history":[{"text":"Original<br>post","attachments":[$photo,$video]}]}""")
        val prepared = adapter.prepare(source, false) as VkPreparedSource
        assertEquals("Comment\n\nOriginal\npost", prepared.post.text)
        assertEquals(listOf(PostMediaKind.PHOTO, PostMediaKind.VIDEO), prepared.mediaKinds)
    }

    @Test
    fun supportsTextOnlyAndMediaWithoutTextButRejectsEmptyPost() = runTest {
        coEvery { client.fetch(any()) } returns mapper.readTree("""{"text":"Just text","attachments":[{"type":"audio"}]}""")
        assertEquals(emptyList(), (adapter.prepare(source, true) as VkPreparedSource).items)
        coEvery { client.fetch(any()) } returns mapper.readTree("""{"text":"","attachments":[$photo]}""")
        assertNull((adapter.prepare(source, true) as VkPreparedSource).post.text)
        coEvery { client.fetch(any()) } returns mapper.readTree("""{"text":"","attachments":[{"type":"audio"}]}""")
        assertFailsWith<DownloadFailure> { adapter.prepare(source, true) }
    }

    @Test
    fun rejectsMissingSupportedMediaUnexpectedHostsAndOversizedCollections() = runTest {
        val invalid = listOf(
            """{"attachments":[{"type":"photo","photo":{}}]}""",
            """{"attachments":[{"type":"video","video":{}}]}""",
            """{"attachments":[${photo.replace("sun9-1.userapi.com", "localhost")}]}""",
            "{\"attachments\":[${List(21) { photo }.joinToString(",")}]}",
        )
        for (json in invalid) {
            coEvery { client.fetch(any()) } returns mapper.readTree(json)
            assertFailsWith<DownloadFailure> { adapter.prepare(source, false) }
        }
    }

    @Test
    fun preparesStandaloneVideoWithoutRequestingWall() = runTest {
        val request = DownloadRequestMapper.source(testPlatformResolver().resolve("https://vk.com/video-1_2").video)
        val prepared = YtDlpPreparedSource(MediaMetadata())
        coEvery { ytDlp.extractCatalogMetadata(request) } returns mapper.treeToValue(mapper.readTree("{}"), com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto::class.java)
        assertEquals(prepared, adapter.prepare(request, true))
        coVerify(exactly = 0) { client.fetch(any()) }
    }
}
