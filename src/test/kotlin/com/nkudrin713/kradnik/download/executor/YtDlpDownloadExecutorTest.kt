package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.VK_VIDEO_PRESET
import com.nkudrin713.kradnik.ytdlp.client.YtDlpException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class YtDlpDownloadExecutorTest {
    private val ytDlpService: YtDlpService = mockk()
    private val executor = YtDlpDownloadExecutor(ytDlpService)

    @Test
    fun registersAllYtDlpStrategies() {
        assertEquals(
            setOf(
                DownloadStrategy.YT_DLP,
                DownloadStrategy.YOUTUBE_YT_DLP,
                DownloadStrategy.VK_YT_DLP,
            ),
            executor.strategies,
        )
    }

    @Test
    fun preparesAndDownloadsOriginalVkUrl() = runTest {
        val request = request()
        val metadata: YtDlpMetadataDto = mockk()
        val outputDir = Path.of("/tmp/vk-output")
        val downloadedFile = DownloadedFile(outputDir.resolve("video.mp4"), 100)
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        coEvery { ytDlpService.download(request, outputDir) } returns downloadedFile

        val preparation = assertIs<DownloadPreparation.Ready>(executor.prepare(request))

        assertEquals(metadata, preparation.session.metadata)
        assertEquals(downloadedFile, preparation.session.download(request, outputDir))
        coVerify(exactly = 1) { ytDlpService.extractMetadata(request) }
        coVerify(exactly = 1) { ytDlpService.download(request, outputDir) }
    }

    @Test
    fun preparesCatalogThroughSameStrategy() = runTest {
        val request = request()
        val metadata: YtDlpMetadataDto = mockk()
        coEvery { ytDlpService.extractCatalogMetadata(request) } returns metadata

        val preparation = assertIs<DownloadPreparation.Ready>(executor.prepareCatalog(request))

        assertEquals(metadata, preparation.session.metadata)
        coVerify(exactly = 1) { ytDlpService.extractCatalogMetadata(request) }
        coVerify(exactly = 0) { ytDlpService.extractMetadata(request) }
    }

    @Test
    fun propagatesFailureWithoutAnotherStrategy() = runTest {
        val request = request()
        coEvery { ytDlpService.extractMetadata(request) } throws YtDlpException("VK failed")

        assertFailsWith<YtDlpException> {
            executor.prepare(request)
        }

        coVerify(exactly = 1) { ytDlpService.extractMetadata(request) }
    }

    private fun request(): DownloadSpec {
        return DownloadSpec(
            originalUrl = "https://m.vk.com/video-1_2?list=access-token",
            normalizedUrl = "https://vk.com/video-1_2",
            cacheKey = "vk:video:-1_2",
            outputType = OutputType.VIDEO,
            strategy = DownloadStrategy.VK_YT_DLP,
            formatSelector = "format",
            presetName = VK_VIDEO_PRESET,
        )
    }
}
