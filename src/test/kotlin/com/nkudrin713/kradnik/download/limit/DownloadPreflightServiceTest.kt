package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.DownloadRequest
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.ytdlp.client.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.client.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class DownloadPreflightServiceTest {
    private val ytDlpService: YtDlpService = mockk()
    private val service = DownloadPreflightService(ytDlpService)

    @Test
    fun allowsUnknownSize() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(filesize = null, filesizeApprox = null)

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun rejectsLargeAudio() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1)

        val actual = service.check(audioRequest())

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeNonVerticalVideo() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            width = 1920,
            height = 1080,
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun allowsLargeVerticalVideoForCompressionPath() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            width = 1080,
            height = 1920,
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun sumsRequestedFormatsWhenTopLevelSizeIsMissing() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            requestedFormats = listOf(
                YtDlpFormatDto(
                    formatId = "video",
                    ext = "mp4",
                    width = 1920,
                    height = 1080,
                    filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES,
                    filesizeApprox = null,
                ),
                YtDlpFormatDto(
                    formatId = "audio",
                    ext = "m4a",
                    width = null,
                    height = null,
                    filesize = 1,
                    filesizeApprox = null,
                ),
            )
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    private fun metadata(
        filesize: Long? = null,
        filesizeApprox: Long? = null,
        width: Int? = 1920,
        height: Int? = 1080,
        requestedFormats: List<YtDlpFormatDto>? = null,
    ): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = "id",
            title = "title",
            extractor = "youtube",
            webpageUrl = "https://example.com",
            thumbnail = null,
            duration = 120,
            ext = "mp4",
            width = width,
            height = height,
            fps = null,
            filesize = filesize,
            vcodec = null,
            acodec = null,
            filesizeApprox = filesizeApprox,
            formatId = "format",
            format = null,
            requestedFormats = requestedFormats,
        )
    }

    private fun videoRequest(): DownloadRequest {
        return request(OutputType.VIDEO)
    }

    private fun audioRequest(): DownloadRequest {
        return request(OutputType.AUDIO)
    }

    private fun request(outputType: OutputType): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://example.com",
            normalizedUrl = "https://example.com",
            outputType = outputType,
            formatSelector = "format",
            presetName = "preset",
        )
    }
}
