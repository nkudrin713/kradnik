package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadPreflightServiceTest {
    private val uploadLimits = TelegramUploadLimits(TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES)
    private val service = DownloadPreflightService(
        audioUploadPlanner = AudioUploadPlanner(uploadLimits),
        uploadLimits = uploadLimits,
    )

    @Test
    fun allowsUnknownSize() {
        val actual = service.check(videoRequest(), metadata(filesize = null, filesizeApprox = null))

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun rejectsLargeAudioWhenDurationIsUnknown() {
        val actual = service.check(
            audioRequest(),
            metadata(
                durationSeconds = null,
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun allowsLargeSourceAudioWithAdaptiveQuality() {
        val actual = service.check(
            audioRequest(extraArgs = listOf("-x", "--audio-format", "mp3")),
            metadata(
                durationSeconds = 8604,
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
            ),
        )

        assertEquals(
            listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"),
            assertIs<DownloadPreflightDecision.Allowed>(actual).spec.extraArgs,
        )
    }

    @Test
    fun rejectsLargeSourceAudioWhenAdaptiveQualityCannotFitLimit() {
        val actual = service.check(
            audioRequest(extraArgs = listOf("-x", "--audio-format", "mp3")),
            metadata(
                durationSeconds = 4 * 60 * 60,
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun allowsSizeEqualToLimit() {
        val actual = service.check(audioRequest(), metadata(filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES))

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun localModeAllowsTwoBillionBytesAndRejectsMore() {
        val localLimits = TelegramUploadLimits(TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES)
        val localService = DownloadPreflightService(
            audioUploadPlanner = AudioUploadPlanner(localLimits),
            uploadLimits = localLimits,
        )

        val allowed = localService.check(
            audioRequest(),
            metadata(durationSeconds = null, filesize = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES),
        )
        val rejected = localService.check(
            audioRequest(),
            metadata(durationSeconds = null, filesize = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES + 1),
        )

        assertIs<DownloadPreflightDecision.Allowed>(allowed)
        assertIs<DownloadPreflightDecision.Rejected>(rejected)
    }

    @Test
    fun usesApproximateSizeWhenExactSizeIsMissing() {
        val actual = service.check(
            audioRequest(),
            metadata(
                durationSeconds = null,
                filesize = null,
                filesizeApprox = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
    }

    @Test
    fun rejectsLargeNonVerticalVideo() {
        val actual = service.check(
            videoRequest(),
            metadata(
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
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
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
                width = 1080,
                height = 1920,
            ),
        )

        assertIs<DownloadPreflightDecision.Allowed>(actual)
    }

    @Test
    fun localModeRejectsLargeVerticalVideo() {
        val localLimits = TelegramUploadLimits(
            maxUploadBytes = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES,
            localMode = true,
        )
        val localService = DownloadPreflightService(
            audioUploadPlanner = AudioUploadPlanner(localLimits),
            uploadLimits = localLimits,
        )

        val actual = localService.check(
            videoRequest(),
            metadata(
                filesize = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES + 1,
                width = 1080,
                height = 1920,
            ),
        )

        assertIs<DownloadPreflightDecision.Rejected>(actual)
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
                        filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES,
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
                        filesizeApprox = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES,
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
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
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
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
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
                durationSeconds = null,
                filesize = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
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
        durationSeconds: Long? = 120,
        requestedFormats: List<YtDlpFormatDto>? = null,
    ): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = "id",
            title = "title",
            extractor = "youtube",
            webpageUrl = "https://example.com",
            thumbnail = null,
            duration = durationSeconds?.let { BigDecimal.valueOf(it) },
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

    private fun videoRequest(): DownloadSpec {
        return request(OutputType.VIDEO)
    }

    private fun audioRequest(): DownloadSpec {
        return request(OutputType.AUDIO)
    }

    private fun audioRequest(extraArgs: List<String>): DownloadSpec {
        return request(OutputType.AUDIO, extraArgs)
    }

    private fun request(
        outputType: OutputType,
        extraArgs: List<String> = emptyList(),
    ): DownloadSpec {
        return DownloadSpec(
            originalUrl = "https://example.com",
            normalizedUrl = "https://example.com",
            cacheKey = "video",
            outputType = outputType,
            strategy = DownloadStrategy.YT_DLP,
            formatSelector = "format",
            extraArgs = extraArgs,
            presetName = "preset",
        )
    }
}
