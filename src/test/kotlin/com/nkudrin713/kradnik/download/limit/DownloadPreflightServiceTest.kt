package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadPreflightServiceTest {
    private val service = DownloadPreflightService()

    @Test
    fun allowsUnknownSize() {
        val actual = service.check(videoRequest(), metadata(filesize = null, filesizeApprox = null))

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun rejectsLargeAudio() {
        val actual = service.check(audioRequest(), metadata(filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1))

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun allowsSizeEqualToLimit() {
        val actual = service.check(audioRequest(), metadata(filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES))

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun usesApproximateSizeWhenExactSizeIsMissing() {
        val actual = service.check(
            audioRequest(),
            metadata(
                filesize = null,
                filesizeApprox = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeNonVerticalVideo() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
                width = 1920,
                height = 1080,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun allowsLargeVerticalVideoForCompressionPath() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
                width = 1080,
                height = 1920,
            ),
        )

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun sumsRequestedFormatsWhenTopLevelSizeIsMissing() {
        val actual = service.check(
            videoRequest(),
            metadata(
                requestedFormats = listOf(
                    format(
                        formatId = "video",
                        ext = "mp4",
                        width = 1920,
                        height = 1080,
                        filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES,
                        filesizeApprox = null,
                    ),
                    format(
                        formatId = "audio",
                        ext = "m4a",
                        width = null,
                        height = null,
                        filesize = 1,
                        filesizeApprox = null,
                    ),
                )
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun allowsEmptyRequestedFormats() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = null,
                filesizeApprox = null,
                requestedFormats = emptyList(),
            ),
        )

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun allowsRequestedFormatsWithUnknownSize() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = null,
                filesizeApprox = null,
                requestedFormats = listOf(
                    format(
                        formatId = "video",
                        ext = "mp4",
                        width = 1920,
                        height = 1080,
                        filesize = null,
                        filesizeApprox = null,
                    ),
                ),
            ),
        )

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun sumsApproximateRequestedFormatSizes() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = null,
                filesizeApprox = null,
                requestedFormats = listOf(
                    format(
                        formatId = "video",
                        ext = "mp4",
                        width = 1920,
                        height = 1080,
                        filesize = null,
                        filesizeApprox = TelegramUploadLimits.MAX_UPLOAD_BYTES,
                    ),
                    format(
                        formatId = "audio",
                        ext = "m4a",
                        width = null,
                        height = null,
                        filesize = null,
                        filesizeApprox = 1,
                    ),
                ),
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeVideoWhenWidthIsMissing() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
                width = null,
                height = 1920,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeVideoWhenHeightIsMissing() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
                width = 1080,
                height = null,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectedReasonContainsFormattedSize() {
        val actual = service.check(
            audioRequest(),
            metadata(
                filesize = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            ),
        )

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
            duration = BigDecimal.valueOf(120),
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
            track = null,
            artist = null,
            creator = null,
            uploader = null,
            channel = null,
            requestedFormats = requestedFormats,
        )
    }

    private fun format(
        formatId: String,
        ext: String,
        width: Int?,
        height: Int?,
        filesize: Long?,
        filesizeApprox: Long?,
    ): YtDlpFormatDto {
        return YtDlpFormatDto(
            formatId = formatId,
            formatNote = null,
            ext = ext,
            width = width,
            height = height,
            fps = null,
            filesize = filesize,
            filesizeApprox = filesizeApprox,
            vcodec = null,
            acodec = null,
            tbr = null,
            vbr = null,
            abr = null,
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
