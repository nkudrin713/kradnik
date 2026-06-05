package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun allowsSizeEqualToLimit() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES)

        val actual = service.check(audioRequest())

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun usesApproximateSizeWhenExactSizeIsMissing() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = null,
            filesizeApprox = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
        )

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

    @Test
    fun allowsEmptyRequestedFormats() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = null,
            filesizeApprox = null,
            requestedFormats = emptyList(),
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun allowsRequestedFormatsWithUnknownSize() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = null,
            filesizeApprox = null,
            requestedFormats = listOf(
                YtDlpFormatDto(
                    formatId = "video",
                    ext = "mp4",
                    width = 1920,
                    height = 1080,
                    filesize = null,
                    filesizeApprox = null,
                ),
            ),
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun sumsApproximateRequestedFormatSizes() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = null,
            filesizeApprox = null,
            requestedFormats = listOf(
                YtDlpFormatDto(
                    formatId = "video",
                    ext = "mp4",
                    width = 1920,
                    height = 1080,
                    filesize = null,
                    filesizeApprox = TelegramUploadLimits.MAX_UPLOAD_BYTES,
                ),
                YtDlpFormatDto(
                    formatId = "audio",
                    ext = "m4a",
                    width = null,
                    height = null,
                    filesize = null,
                    filesizeApprox = 1,
                ),
            ),
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeVideoWhenWidthIsMissing() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            width = null,
            height = 1920,
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeVideoWhenHeightIsMissing() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            width = 1080,
            height = null,
        )

        val actual = service.check(videoRequest())

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectedReasonContainsFormattedSize() = runTest {
        coEvery { ytDlpService.inspect(any()) } returns metadata(
            filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
        )

        val actual = service.check(audioRequest())

        assertEquals(
            "Selected audio is too large for Telegram: sizeMb=45.00, limitMb=45.00",
            assertIs<DownloadPreflightDecision.Rejected>(actual).reason,
        )
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
