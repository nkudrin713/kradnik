package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.DownloadPreparation
import com.nkudrin713.kradnik.download.PreparedDownloadSession
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadChoicePlannerTest {
    private val platformResolver: PlatformResolver = mockk()
    private val downloadEngine: DownloadEngine = mockk()
    private val uploadLimits = TelegramUploadLimits(2_000_000_000, localMode = true)
    private val planner = DownloadChoicePlanner(
        platformResolver = platformResolver,
        downloadEngine = downloadEngine,
        audioUploadPlanner = AudioUploadPlanner(uploadLimits),
        uploadLimits = uploadLimits,
    )

    @Test
    fun buildsOriginalNamedQualitiesAudioAndCoverFromSingleCatalog() = runTest {
        val video = resolved(OutputType.VIDEO)
        val audio = resolved(OutputType.AUDIO)
        every { platformResolver.resolve(URL) } returns PlatformDownloadSpecs(video, audio)
        coEvery { downloadEngine.prepareCatalog(video) } returns prepared(metadata(
            formats = listOf(
                videoFormat("v1440", 1440, 600_000_000),
                videoFormat("v1080", 1080, 400_000_000),
                videoFormat("v720", 720, 250_000_000),
                videoFormat("v480", 480, 150_000_000),
                videoFormat("v360", 360, 100_000_000),
                audioFormat("a1", 20_000_000),
            ),
        ))

        val actual = planner.plan(URL)

        assertEquals(
            listOf("video_original", "video_1080", "video_720", "video_480", "video_360", "audio", "cover"),
            actual.options.map { it.key },
        )
        val original = actual.options.first()
        assertEquals("v1440+a1", original.spec.formatSelector)
        assertEquals(620_000_000, original.sizeBytes)
        assertFalse(original.approximateSize)
        assertEquals("v720+a1", actual.options.first { it.key == "video_720" }.spec.formatSelector)
        assertTrue(actual.options.first { it.key == "audio" }.approximateSize)
        assertEquals(OutputType.COVER, actual.options.last().spec.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, actual.options.last().spec.platform)
        assertEquals(actual.options.size, actual.options.map { it.spec.cacheKey }.distinct().size)
        assertEquals(DownloadChoiceMediaInfo("Channel", "Title", 120), actual.mediaInfo)
        coVerify(exactly = 1) { downloadEngine.prepareCatalog(video) }
    }

    @Test
    fun omitsUnavailableResolutionAndMarksOversizedOriginalUnavailable() = runTest {
        val video = resolved(OutputType.VIDEO)
        every { platformResolver.resolve(URL) } returns PlatformDownloadSpecs(video, resolved(OutputType.AUDIO))
        coEvery { downloadEngine.prepareCatalog(video) } returns prepared(metadata(
            formats = listOf(
                videoFormat("v1440", 1440, 1_990_000_000),
                videoFormat("v720", 720, 200_000_000),
                audioFormat("a1", 20_000_000),
            ),
        ))

        val actual = planner.plan(URL)

        assertEquals(listOf("video_original", "video_720", "audio", "cover"), actual.options.map { it.key })
        val original = actual.options.first()
        assertFalse(original.available)
        assertEquals("Размер превышает лимит Telegram", original.unavailableReason)
    }

    @Test
    fun usesApproximateBitrateSizeWhenFormatSizeIsMissing() = runTest {
        val video = resolved(OutputType.VIDEO)
        every { platformResolver.resolve(URL) } returns PlatformDownloadSpecs(video, resolved(OutputType.AUDIO))
        coEvery { downloadEngine.prepareCatalog(video) } returns prepared(metadata(
            formats = listOf(
                videoFormat("v720", 720, size = null, bitrate = 1_000),
                audioFormat("a1", size = null, bitrate = 100),
            ),
        ))

        val option = planner.plan(URL).options.first { it.key == "video_original" }

        assertEquals(16_500_000, option.sizeBytes)
        assertTrue(option.approximateSize)
    }

    @Test
    fun loadsInstagramCatalogThroughDownloadEngine() = runTest {
        val video = resolved(OutputType.VIDEO).withInstagramPlatform()
        val audio = resolved(OutputType.AUDIO).withInstagramPlatform()
        val catalog = metadata(
            formats = listOf(
                videoFormat("v720", 720, 200_000_000),
                audioFormat("a1", 20_000_000),
            ),
        )
        every { platformResolver.resolve(URL) } returns PlatformDownloadSpecs(video, audio)
        coEvery { downloadEngine.prepareCatalog(video) } returns prepared(catalog)

        val actual = planner.plan(URL)

        assertEquals(DownloadPlatform.INSTAGRAM, actual.options.first().spec.platform)
        coVerify(exactly = 1) { downloadEngine.prepareCatalog(video) }
    }

    private fun resolved(outputType: OutputType): DownloadSpec {
        val preset = if (outputType == OutputType.AUDIO) "youtube_audio" else "youtube_h264_mobile_2gb"
        return DownloadSpec(
            originalUrl = URL,
            normalizedUrl = URL,
            cacheKey = "youtube:video:id:${outputType.dbValue}:$preset",
            outputType = outputType,
            platform = DownloadPlatform.YOUTUBE,
            formatSelector = if (outputType == OutputType.AUDIO) "ba" else "best",
            extraArgs = if (outputType == OutputType.AUDIO) {
                listOf("-x", "--audio-format", "mp3")
            } else {
                listOf("--merge-output-format", "mp4")
            },
            presetName = preset,
        )
    }

    private fun DownloadSpec.withInstagramPlatform(): DownloadSpec {
        return copy(
            platform = DownloadPlatform.INSTAGRAM,
            presetName = if (outputType == OutputType.AUDIO) {
                "instagram_audio"
            } else {
                "instagram_mobile_video"
            },
        )
    }

    private fun prepared(metadata: YtDlpMetadataDto): DownloadPreparation.Ready {
        val session: PreparedDownloadSession = mockk {
            every { this@mockk.metadata } returns metadata
        }
        return DownloadPreparation.Ready(session)
    }

    private fun videoFormat(
        id: String,
        height: Int,
        size: Long?,
        bitrate: Long = 2_000,
    ): YtDlpFormatDto {
        return format(
            id = id,
            height = height,
            size = size,
            vcodec = "avc1.640028",
            acodec = "none",
            tbr = bitrate,
        )
    }

    private fun audioFormat(id: String, size: Long?, bitrate: Long = 128): YtDlpFormatDto {
        return format(
            id = id,
            height = null,
            size = size,
            vcodec = "none",
            acodec = "mp4a.40.2",
            tbr = bitrate,
        )
    }

    private fun format(
        id: String,
        height: Int?,
        size: Long?,
        vcodec: String,
        acodec: String,
        tbr: Long,
    ): YtDlpFormatDto {
        return YtDlpFormatDto(
            formatId = id,
            ext = if (height == null) "m4a" else "mp4",
            height = height,
            fps = if (height == null) null else BigDecimal.valueOf(30),
            filesize = size,
            filesizeApprox = null,
            vcodec = vcodec,
            acodec = acodec,
            tbr = BigDecimal.valueOf(tbr),
            vbr = null,
            abr = if (height == null) BigDecimal.valueOf(tbr) else null,
        )
    }

    private fun metadata(formats: List<YtDlpFormatDto>): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            title = "Title",
            extractor = "youtube",
            thumbnail = "https://i.ytimg.com/vi/id/maxresdefault.jpg",
            duration = BigDecimal.valueOf(120),
            width = null,
            height = null,
            filesize = null,
            filesizeApprox = null,
            track = null,
            artist = null,
            uploader = "Uploader",
            channel = "Channel",
            requestedFormats = null,
            formats = formats,
        )
    }

    private companion object {
        private const val URL = "https://www.youtube.com/watch?v=id"
    }
}
