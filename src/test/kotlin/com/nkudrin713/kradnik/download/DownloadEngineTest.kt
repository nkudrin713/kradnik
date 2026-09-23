package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.cover.CoverDownloader
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.client.YtDlpException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
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

class DownloadEngineTest {
    private val ytDlpService: YtDlpService = mockk()
    private val instagramDownloader: InstagramEmbedDownloader = mockk()
    private val coverDownloader: CoverDownloader = mockk()
    private val engine = DownloadEngine(ytDlpService, instagramDownloader, coverDownloader)

    @Test
    fun preparesAndDownloadsVkVideo() = runTest {
        val spec = spec(DownloadPlatform.VK)
        val metadata: YtDlpMetadataDto = mockk()
        val outputDir = Path.of("/tmp/vk-output")
        val downloadedFile = DownloadedFile(outputDir.resolve("video.mp4"), 100)
        coEvery { ytDlpService.extractMetadata(spec) } returns metadata
        coEvery { ytDlpService.download(spec, outputDir) } returns downloadedFile

        val preparation = engine.prepare(spec)

        assertEquals(metadata, preparation.metadata)
        assertEquals(downloadedFile, engine.download(spec, preparation, outputDir))
    }

    @Test
    fun extractsCatalogMetadataForChoices() = runTest {
        val spec = spec(DownloadPlatform.YOUTUBE)
        val metadata: YtDlpMetadataDto = mockk()
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns metadata

        val preparation = engine.prepare(spec, catalog = true)

        assertEquals(metadata, preparation.metadata)
        coVerify(exactly = 0) { ytDlpService.extractMetadata(spec) }
    }

    @Test
    fun preparesCoverFromSourceMetadata(@TempDir outputDir: Path) = runTest {
        val spec = spec(DownloadPlatform.VK, OutputType.COVER)
        val thumbnailUrl = "https://example.com/cover.jpg"
        val metadata = mockk<YtDlpMetadataDto> {
            every { thumbnail } returns thumbnailUrl
        }
        val file = DownloadedFile(outputDir.resolve("cover.jpg"), 100)
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns metadata
        coEvery { coverDownloader.download(thumbnailUrl, outputDir) } returns file

        val preparation = engine.prepare(spec)

        assertEquals(file, engine.download(spec, preparation, outputDir))
    }

    @Test
    fun rejectsCoverWithoutThumbnail() = runTest {
        val spec = spec(DownloadPlatform.YOUTUBE, OutputType.COVER)
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns mockk {
            every { thumbnail } returns null
        }

        assertFailsWith<IllegalArgumentException> {
            engine.download(spec, engine.prepare(spec), Path.of("/tmp/cover"))
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
        val prepared = mockk<com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload> {
            every { metadata } returns mockk(relaxed = true)
            every { mediaUri } returns java.net.URI("https://cdn.instagram.com/video.mp4")
        }
        val dir = Path.of("/tmp/instagram")
        val file = DownloadedFile(dir.resolve("media"), 10)
        coEvery { instagramDownloader.prepare(any()) } returns prepared
        coEvery { instagramDownloader.download(prepared, dir) } returns file
        coEvery { ytDlpService.download(any(), dir) } returns file
        assertEquals(file, engine.download(video, engine.prepare(video), dir))
        val audio = video.copy(outputType = OutputType.AUDIO)
        assertEquals(file, engine.download(audio, engine.prepare(audio), dir))
        coVerify(exactly = 1) { instagramDownloader.download(prepared, dir) }
        coVerify(exactly = 1) { ytDlpService.download(audio, dir) }
    }

    @Test
    fun instagramWithoutDirectUrlUsesYtDlp() = runTest {
        val spec = spec(DownloadPlatform.INSTAGRAM)
        val prepared = mockk<com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload> {
            every { metadata } returns mockk(relaxed = true)
            every { mediaUri } returns null
        }
        val dir = Path.of("/tmp/instagram")
        val file = DownloadedFile(dir.resolve("media"), 10)
        coEvery { instagramDownloader.prepare(spec) } returns prepared
        coEvery { ytDlpService.download(spec, dir) } returns file
        assertEquals(file, engine.download(spec, engine.prepare(spec), dir))
    }

    private fun spec(
        platform: DownloadPlatform,
        outputType: OutputType = OutputType.VIDEO,
    ): DownloadSpec {
        return DownloadSpec(
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            cacheKey = "cache-key",
            outputType = outputType,
            platform = platform,
            formatSelector = "format",
            presetName = "preset",
        )
    }
}
