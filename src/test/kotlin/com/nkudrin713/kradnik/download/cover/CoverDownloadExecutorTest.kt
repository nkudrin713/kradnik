package com.nkudrin713.kradnik.download.cover

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.instagram.InstagramDownloadExecutor
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
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
import kotlin.test.assertIs

class CoverDownloadExecutorTest {
    private val ytDlpService: YtDlpService = mockk()
    private val instagramExecutor: InstagramDownloadExecutor = mockk()
    private val coverDownloader: CoverDownloader = mockk()
    private val executor = CoverDownloadExecutor(ytDlpService, instagramExecutor, coverDownloader)

    @Test
    fun preparesAndDownloadsYtDlpCover(@TempDir tempDir: Path) = runTest {
        val request = request("youtube_cover")
        val coverUrl = "https://example.com/cover.jpg"
        val metadata = metadata(coverUrl)
        val file = DownloadedFile(tempDir.resolve("cover.jpg"), 100)
        coEvery { ytDlpService.extractCatalogMetadata(request) } returns metadata
        coEvery { coverDownloader.download(coverUrl, tempDir) } returns file

        val preparation = assertIs<DownloadPreparation.Ready>(executor.prepare(request))

        assertEquals(file, preparation.session.download(request, tempDir))
        coVerify(exactly = 1) { ytDlpService.extractCatalogMetadata(request) }
    }

    @Test
    fun rejectsMetadataWithoutCover() = runTest {
        val request = request("vk_cover")
        coEvery { ytDlpService.extractCatalogMetadata(request) } returns metadata(null)

        assertIs<DownloadPreparation.TerminalFailure>(executor.prepare(request))
    }

    @Test
    fun registersExplicitCoverStrategies() {
        assertEquals(
            setOf(DownloadStrategy.COVER_YT_DLP, DownloadStrategy.COVER_INSTAGRAM_EMBED),
            executor.strategies,
        )
    }

    private fun request(preset: String): DownloadSpec {
        return DownloadSpec(
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            cacheKey = "cover",
            outputType = OutputType.COVER,
            strategy = DownloadStrategy.COVER_YT_DLP,
            formatSelector = "best",
            presetName = preset,
        )
    }

    private fun metadata(thumbnailUrl: String?): YtDlpMetadataDto {
        return mockk {
            every { thumbnail } returns thumbnailUrl
        }
    }
}
