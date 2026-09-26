package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.cover.CoverDownloader
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.instagram.InstagramContentUnavailableException
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedException
import com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.single.CoverHandler
import com.nkudrin713.kradnik.download.source.InstagramPreparedSource
import com.nkudrin713.kradnik.download.source.InstagramSourceAdapter
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.download.source.YtDlpSourceAdapter
import com.nkudrin713.kradnik.download.source.toMediaMetadata
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DownloadEngineTest {
    private val ytDlpService: YtDlpService = mockk()
    private val instagramDownloader: InstagramEmbedDownloader = mockk()
    private val coverDownloader: CoverDownloader = mockk()
    private val engine = DownloadEngine(YtDlpSourceAdapter(ytDlpService), InstagramSourceAdapter(instagramDownloader, ytDlpService))

    @Test
    fun preparesAndDownloadsVkVideo() = runTest {
        val spec = spec(DownloadPlatform.VK)
        val metadata: YtDlpMetadataDto = mockk(relaxed = true)
        val outputDir = Path.of("/tmp/vk-output")
        val downloadedFile = DownloadedFile(outputDir.resolve("video.mp4"), 100)
        coEvery { ytDlpService.extractMetadata(spec) } returns metadata
        coEvery { ytDlpService.download(spec, outputDir) } returns downloadedFile

        val preparation = engine.prepare(spec)

        assertEquals(metadata.toMediaMetadata(), preparation.metadata)
        assertEquals(downloadedFile, engine.download(spec, SourceMediaType.VIDEO, preparation, outputDir))
    }

    @Test
    fun extractsCatalogMetadataForChoices() = runTest {
        val spec = spec(DownloadPlatform.YOUTUBE)
        val metadata: YtDlpMetadataDto = mockk(relaxed = true)
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns metadata

        val preparation = engine.prepare(spec, catalog = true)

        assertEquals(metadata.toMediaMetadata(), preparation.metadata)
        coVerify(exactly = 0) { ytDlpService.extractMetadata(spec) }
    }

    @Test
    fun preparesCoverFromSourceMetadata(@TempDir outputDir: Path) = runTest {
        val spec = spec(DownloadPlatform.VK, OutputType.COVER)
        val thumbnailUrl = "https://example.com/cover.jpg"
        val metadata = mockk<YtDlpMetadataDto>(relaxed = true) {
            every { thumbnail } returns thumbnailUrl
        }
        val file = DownloadedFile(outputDir.resolve("cover.jpg"), 100)
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns metadata
        coEvery { coverDownloader.download(thumbnailUrl, outputDir) } returns file

        assertEquals(MediaArtifact.Document(file.file), coverHandler().produce(SingleMediaRequest(spec, OutputType.COVER, "key"), outputDir, 1, JobProgress {}))
    }

    @Test
    fun rejectsCoverWithoutThumbnail() = runTest {
        val spec = spec(DownloadPlatform.YOUTUBE, OutputType.COVER)
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns mockk(relaxed = true) {
            every { thumbnail } returns null
        }

        assertFailsWith<IllegalArgumentException> {
            coverHandler().produce(SingleMediaRequest(spec, OutputType.COVER, "key"), Path.of("/tmp/cover"), 1, JobProgress {})
        }
    }

    @Test
    fun propagatesYtDlpFailure() = runTest {
        val spec = spec(DownloadPlatform.VK)
        coEvery { ytDlpService.extractMetadata(spec) } throws YtDlpException("VK failed")

        assertFailsWith<YtDlpException> {
            engine.prepare(spec)
        }
    }

    @Test
    fun instagramUsesDirectVideoButYtDlpForAudio() = runTest {
        val video = spec(DownloadPlatform.INSTAGRAM)
        val prepared = mockk<com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload>(relaxed = true) {
            every { metadata } returns mockk(relaxed = true)
            every { mediaUri } returns java.net.URI("https://cdn.instagram.com/video.mp4")
        }
        val dir = Path.of("/tmp/instagram")
        val file = DownloadedFile(dir.resolve("media"), 10)
        coEvery { instagramDownloader.prepare(any()) } returns prepared
        coEvery { instagramDownloader.download(prepared, dir) } returns file
        coEvery { ytDlpService.download(any(), dir) } returns file
        assertEquals(file, engine.download(video, SourceMediaType.VIDEO, engine.prepare(video), dir))
        val audio = video
        assertEquals(file, engine.download(audio, SourceMediaType.AUDIO, engine.prepare(audio), dir))
        coVerify(exactly = 1) { instagramDownloader.download(prepared, dir) }
        coVerify(exactly = 1) { ytDlpService.download(audio, dir) }
    }

    @Test
    fun instagramWithoutDirectUrlUsesYtDlp() = runTest {
        val spec = spec(DownloadPlatform.INSTAGRAM)
        val prepared = mockk<com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload>(relaxed = true) {
            every { metadata } returns mockk(relaxed = true)
            every { mediaUri } returns null
        }
        val dir = Path.of("/tmp/instagram")
        val file = DownloadedFile(dir.resolve("media"), 10)
        coEvery { instagramDownloader.prepare(spec) } returns prepared
        coEvery { ytDlpService.download(spec, dir) } returns file
        assertEquals(file, engine.download(spec, SourceMediaType.VIDEO, engine.prepare(spec), dir))
    }

    @Test
    fun fallsBackToStaticInstagramImagesAndDownloadsTheGroup() = runTest {
        val videoSpec = spec(DownloadPlatform.INSTAGRAM)
        val imageSpec = videoSpec
        val metadata: YtDlpMetadataDto = mockk(relaxed = true)
        val prepared = mockk<InstagramPreparedDownload>(relaxed = true) {
            every { this@mockk.metadata } returns metadata
        }
        val preparation = InstagramPreparedSource(prepared)
        val outputDir = Path.of("/tmp/instagram-images")
        val downloaded = DownloadedFile(
            file = outputDir.resolve("01.jpg"),
            sizeBytes = 20,
            additionalFiles = listOf(outputDir.resolve("02.jpg")),
        )
        coEvery { instagramDownloader.prepare(videoSpec) } throws InstagramContentUnavailableException()
        coEvery { ytDlpService.extractInstagramImageMetadata(videoSpec) } returns metadata
        every { instagramDownloader.prepareImages(videoSpec, metadata) } returns prepared
        coEvery { instagramDownloader.downloadImages(prepared, outputDir) } returns downloaded

        assertEquals(preparation, engine.prepare(videoSpec))
        assertEquals(downloaded, engine.download(imageSpec, SourceMediaType.IMAGES, preparation, outputDir))
        coVerify(exactly = 1) { instagramDownloader.downloadImages(prepared, outputDir) }
        coVerify(exactly = 0) { ytDlpService.download(imageSpec, outputDir) }
    }

    @Test
    fun preservesBothFailuresWhenInstagramFallbackAlsoFails() = runTest {
        val request = spec(DownloadPlatform.INSTAGRAM)
        val embedError = InstagramEmbedException("Embed failed")
        val fallbackError = YtDlpException("Fallback failed")
        coEvery { instagramDownloader.prepare(request) } throws embedError
        coEvery { ytDlpService.extractInstagramImageMetadata(request) } throws fallbackError
        val error = assertFailsWith<InstagramEmbedException> { engine.prepare(request) }
        assertSame(embedError, error)
        assertSame(fallbackError, error.suppressed.single())
    }

    private fun coverHandler(): CoverHandler {
        val limits = TelegramUploadLimits(1000)
        return CoverHandler(engine, DownloadPreflightService(AudioUploadPlanner(limits), limits), coverDownloader)
    }

    private fun spec(
        platform: DownloadPlatform,
        outputType: OutputType = OutputType.VIDEO,
    ): SourceRequest {
        return SourceRequest(
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            platform = platform,
            formatSelector = "format",
            presetName = "preset",
        )
    }
}
