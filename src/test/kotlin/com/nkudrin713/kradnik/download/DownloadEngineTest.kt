package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.cover.CoverDownloader
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.instagram.InstagramDownloader
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
import kotlin.test.assertIs

class DownloadEngineTest {
    private val ytDlpService: YtDlpService = mockk()
    private val instagramDownloader: InstagramDownloader = mockk()
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

        val preparation = assertIs<DownloadPreparation.Ready>(engine.prepare(spec))

        assertEquals(metadata, preparation.session.metadata)
        assertEquals(downloadedFile, preparation.session.download(spec, outputDir))
    }

    @Test
    fun extractsCatalogMetadataForChoices() = runTest {
        val spec = spec(DownloadPlatform.YOUTUBE)
        val metadata: YtDlpMetadataDto = mockk()
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns metadata

        val preparation = assertIs<DownloadPreparation.Ready>(engine.prepareCatalog(spec))

        assertEquals(metadata, preparation.session.metadata)
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

        val preparation = assertIs<DownloadPreparation.Ready>(engine.prepare(spec))

        assertEquals(file, preparation.session.download(spec, outputDir))
    }

    @Test
    fun rejectsCoverWithoutThumbnail() = runTest {
        val spec = spec(DownloadPlatform.YOUTUBE, OutputType.COVER)
        coEvery { ytDlpService.extractCatalogMetadata(spec) } returns mockk {
            every { thumbnail } returns null
        }

        assertIs<DownloadPreparation.TerminalFailure>(engine.prepare(spec))
    }

    @Test
    fun propagatesYtDlpFailure() = runTest {
        val spec = spec(DownloadPlatform.VK)
        coEvery { ytDlpService.extractMetadata(spec) } throws YtDlpException("VK failed")

        assertFailsWith<YtDlpException> {
            engine.prepare(spec)
        }
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
