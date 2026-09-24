package com.nkudrin713.kradnik.download.instagram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpThumbnailDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InstagramEmbedDownloaderTest {
    private val httpClient: InstagramHttpClient = mockk(relaxed = true)
    private val downloader = InstagramEmbedDownloader(httpClient)

    @Test
    fun preparesDownloadFromEmbedPayload() = runTest {
        val request = request("https://www.instagram.com/reel/ABC_123/")
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        coEvery { httpClient.getText(embedUri) } returns embedHtml()
        coEvery { httpClient.contentLength(URI.create(MEDIA_URL)) } returns 42_000_000

        val prepared = downloader.prepare(request)

        assertEquals("ABC_123", prepared.shortcode)
        assertEquals(URI.create(MEDIA_URL), prepared.mediaUri)
        assertEquals(BigDecimal("12.5"), prepared.metadata.duration)
        assertEquals(720, prepared.metadata.width)
        assertEquals(1280, prepared.metadata.height)
        assertEquals("owner", prepared.metadata.uploader)
        assertEquals(THUMBNAIL_URL, prepared.metadata.thumbnail)
        assertEquals(42_000_000, prepared.metadata.filesize)
    }

    @Test
    fun preparesVideoWithoutMediaUrlForYtDlpDownload() = runTest {
        val request = request("https://www.instagram.com/reel/ABC_123/")
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        coEvery { httpClient.getText(embedUri) } returns embedHtml(mediaUrl = null)

        val prepared = downloader.prepare(request)

        assertEquals(null, prepared.mediaUri)
        assertEquals("ABC_123", prepared.shortcode)
        assertEquals(BigDecimal("12.5"), prepared.metadata.duration)
    }

    @Test
    fun downloadsPreparedMedia(@TempDir tempDir: Path) = runTest {
        val prepared = downloader.prepareWithStubbedPayload()
        val outputFile = tempDir.resolve("instagram-ABC_123.mp4")
        val downloadedFile = DownloadedFile(outputFile, 100)
        coEvery { httpClient.download(URI.create(MEDIA_URL), outputFile) } returns downloadedFile

        assertEquals(downloadedFile, downloader.download(prepared, tempDir))

        coVerify(exactly = 1) { httpClient.download(URI.create(MEDIA_URL), outputFile) }
    }

    @Test
    fun rejectsMediaUrlOutsideInstagramCdn() = runTest {
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        coEvery { httpClient.getText(embedUri) } returns embedHtml("https://example.com/video.mp4")

        assertFailsWith<InstagramEmbedException> {
            downloader.prepare(request("https://www.instagram.com/reel/ABC_123/"))
        }
    }

    @Test
    fun rejectsMissingEmbedPayload() = runTest {
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        coEvery { httpClient.getText(embedUri) } returns "<html></html>"

        assertFailsWith<InstagramEmbedException> {
            downloader.prepare(request("https://www.instagram.com/reel/ABC_123/"))
        }
    }

    @Test
    fun reportsUnavailableContentWhenEmbedContextIsNull() = runTest {
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        val payload = jacksonObjectMapper().writeValueAsString(mapOf("contextJSON" to null))
        coEvery { httpClient.getText(embedUri) } returns "<script>[\"init\",[],[$payload]],</script>"

        assertFailsWith<InstagramContentUnavailableException> {
            downloader.prepare(request("https://www.instagram.com/reel/ABC_123/"))
        }
    }

    @Test
    fun rejectsEmbedPayloadWithoutVideo() = runTest {
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        coEvery { httpClient.getText(embedUri) } returns embedHtml(
            mediaUrl = null,
            isVideo = false,
        )

        assertFailsWith<InstagramEmbedException> {
            downloader.prepare(request("https://www.instagram.com/reel/ABC_123/"))
        }
    }

    @Test
    fun preparesEveryImageFromStaticCarouselMetadata() {
        val metadata = imageMetadata(
            entries = listOf(
                imageMetadata("https://scontent-a.cdninstagram.com/first.jpg"),
                imageMetadata("https://scontent-b.cdninstagram.com/second.jpg"),
            )
        )

        val prepared = assertNotNull(downloader.prepareImages(request("https://www.instagram.com/p/ABC_123/"), metadata))

        assertEquals(
            listOf(
                URI("https://scontent-a.cdninstagram.com/first.jpg"),
                URI("https://scontent-b.cdninstagram.com/second.jpg"),
            ),
            prepared.imageUris,
        )
        assertEquals(null, prepared.metadata.duration)
        assertEquals("https://scontent-a.cdninstagram.com/first.jpg", prepared.metadata.thumbnail)
    }

    @Test
    fun rejectsCarouselWhenAnyImageIsMissing() {
        val metadata = imageMetadata(
            entries = listOf(
                imageMetadata("https://scontent-a.cdninstagram.com/first.jpg"),
                imageMetadata(),
            )
        )

        assertNull(downloader.prepareImages(request("https://www.instagram.com/p/ABC_123/"), metadata))
    }

    @Test
    fun downloadsPreparedImagesInOrder(@TempDir tempDir: Path) = runTest {
        val firstUri = URI("https://scontent-a.cdninstagram.com/first.jpg")
        val secondUri = URI("https://scontent-b.cdninstagram.com/second.jpg")
        val firstFile = tempDir.resolve("instagram-ABC_123-01.jpg")
        val secondFile = tempDir.resolve("instagram-ABC_123-02.jpg")
        val prepared = InstagramPreparedDownload(
            shortcode = "ABC_123",
            mediaUri = null,
            imageUris = listOf(firstUri, secondUri),
            metadata = imageMetadata(),
        )
        coEvery { httpClient.downloadImage(firstUri, firstFile) } returns DownloadedFile(firstFile, 10)
        coEvery { httpClient.downloadImage(secondUri, secondFile) } returns DownloadedFile(secondFile, 20)

        val actual = downloader.downloadImages(prepared, tempDir)

        assertEquals(listOf(firstFile, secondFile), actual.files)
        assertEquals(30, actual.sizeBytes)
    }

    private suspend fun InstagramEmbedDownloader.prepareWithStubbedPayload(): InstagramPreparedDownload {
        val embedUri = URI.create("https://www.instagram.com/p/ABC_123/embed/captioned/")
        coEvery { httpClient.getText(embedUri) } returns embedHtml()
        return prepare(request("https://www.instagram.com/reel/ABC_123/"))
    }

    private fun embedHtml(
        mediaUrl: String? = MEDIA_URL,
        isVideo: Boolean = true,
    ): String {
        val contextJson = jacksonObjectMapper().writeValueAsString(
            mapOf(
                "media" to mapOf(
                    "video_url" to mediaUrl,
                    "is_video" to isVideo,
                    "video_duration" to 12.5,
                    "original_width" to 720,
                    "original_height" to 1280,
                    "display_url" to THUMBNAIL_URL,
                    "user" to mapOf("username" to "owner"),
                ),
            ),
        )
        val payload = jacksonObjectMapper().writeValueAsString(mapOf("contextJSON" to contextJson))
        return "<script>[\"init\",[],[$payload]],</script>"
    }

    private fun imageMetadata(
        imageUrl: String? = null,
        entries: List<YtDlpMetadataDto>? = null,
    ): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            title = "Instagram images",
            thumbnail = null,
            duration = null,
            width = null,
            height = null,
            filesize = null,
            filesizeApprox = null,
            track = null,
            artist = null,
            uploader = "owner",
            channel = "owner",
            requestedFormats = null,
            formats = emptyList(),
            thumbnails = imageUrl?.let { listOf(YtDlpThumbnailDto(id = "original", url = it)) },
            entries = entries,
        )
    }

    private fun request(
        url: String,
        outputType: OutputType = OutputType.VIDEO,
    ): DownloadSpec {
        return DownloadSpec(
            originalUrl = url,
            normalizedUrl = url,
            cacheKey = "instagram",
            outputType = outputType,
            platform = DownloadPlatform.INSTAGRAM,
            formatSelector = "format",
            presetName = "instagram",
        )
    }

    private companion object {
        private const val MEDIA_URL = "https://scontent-test.cdninstagram.com/video.mp4"
        private const val THUMBNAIL_URL = "https://scontent-test.cdninstagram.com/thumbnail.jpg"
    }
}
