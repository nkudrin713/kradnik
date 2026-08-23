package com.nkudrin713.kradnik.download.vk

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.executor.YtDlpDownloadExecutor
import com.nkudrin713.kradnik.download.platform.VK_AUDIO_PRESET
import com.nkudrin713.kradnik.download.platform.VK_VIDEO_PRESET
import com.nkudrin713.kradnik.download.request.DownloadRequest
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VkDownloadExecutorTest {
    private val ytDlpService: YtDlpService = mockk()
    private val executor = VkDownloadExecutor(ytDlpService)

    @Test
    fun supportsVkPresetFamilyIncludingQualityVariants() {
        assertTrue(executor.supports(request(VK_VIDEO_PRESET)))
        assertTrue(executor.supports(request(VK_AUDIO_PRESET)))
        assertTrue(executor.supports(request("vk_video_720")))
        assertFalse(executor.supports(request("youtube_audio")))
    }

    @Test
    fun preparesAndDownloadsOriginalVkUrl() = runTest {
        val request = request(VK_VIDEO_PRESET)
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
    fun propagatesYtDlpFailureWithoutAnotherStrategy() = runTest {
        val request = request(VK_VIDEO_PRESET)
        coEvery { ytDlpService.extractMetadata(request) } throws YtDlpException("VK failed")

        assertFailsWith<YtDlpException> {
            executor.prepare(request)
        }

        coVerify(exactly = 1) { ytDlpService.extractMetadata(request) }
    }

    @Test
    fun genericExecutorDoesNotSupportVkPresets() {
        val genericExecutor = YtDlpDownloadExecutor(ytDlpService)

        assertFalse(genericExecutor.supports(request(VK_VIDEO_PRESET)))
        assertFalse(genericExecutor.supports(request("vk_unknown")))
        assertTrue(genericExecutor.supports(request("youtube_audio")))
    }

    private fun request(presetName: String): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://m.vk.com/video-1_2?list=access-token",
            normalizedUrl = "https://vk.com/video-1_2",
            outputType = OutputType.VIDEO,
            formatSelector = "format",
            presetName = presetName,
        )
    }
}
