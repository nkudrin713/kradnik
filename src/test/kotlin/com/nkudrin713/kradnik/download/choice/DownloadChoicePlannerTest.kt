package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadExecutorResolver
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.executor.PreparedDownloadSession
import com.nkudrin713.kradnik.download.executor.YtDlpDownloadExecutor
import com.nkudrin713.kradnik.download.instagram.InstagramDownloadExecutor
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadChoicePlannerTest {
    private val platformResolver: PlatformResolver = mockk()
    private val ytDlpService: YtDlpService = mockk()
    private val instagramExecutor: InstagramDownloadExecutor = mockk {
        every { strategies } returns setOf(DownloadStrategy.INSTAGRAM_EMBED)
    }
    private val downloadExecutorResolver = DownloadExecutorResolver(
        listOf(YtDlpDownloadExecutor(ytDlpService), instagramExecutor)
    )
    private val uploadLimits = TelegramUploadLimits(2_000_000_000, localMode = true)
    private val planner = DownloadChoicePlanner(
        platformResolver = platformResolver,
        downloadExecutorResolver = downloadExecutorResolver,
        audioUploadPlanner = AudioUploadPlanner(uploadLimits),
        uploadLimits = uploadLimits,
        metadataCache = DownloadChoiceMetadataCache(
            clock = Clock.systemUTC(),
            ttl = Duration.ofMinutes(30),
            maxEntries = 20,
        ),
    )

    @Test
    fun buildsOriginalNamedQualitiesAudioAndCoverFromSingleCatalog() = runTest {
        val video = resolved(OutputType.VIDEO)
        val audio = resolved(OutputType.AUDIO)
        every { platformResolver.resolve(URL, OutputType.VIDEO) } returns video
        every { platformResolver.resolve(URL, OutputType.AUDIO) } returns audio
        coEvery { ytDlpService.extractCatalogMetadata(video) } returns metadata(
            formats = listOf(
                videoFormat("v1440", 1440, 600_000_000),
                videoFormat("v1080", 1080, 400_000_000),
                videoFormat("v720", 720, 250_000_000),
                videoFormat("v480", 480, 150_000_000),
                videoFormat("v360", 360, 100_000_000),
                audioFormat("a1", 20_000_000),
            ),
        )

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
        assertEquals(DownloadStrategy.COVER_YT_DLP, actual.options.last().spec.strategy)
        assertEquals(actual.options.size, actual.options.map { it.spec.cacheKey }.distinct().size)
        assertEquals(DownloadChoiceMediaInfo("Channel", "Title", 120), actual.mediaInfo)
        coVerify(exactly = 1) { ytDlpService.extractCatalogMetadata(video) }
    }

    @Test
    fun reusesCatalogMetadataForRepeatedVideo() = runTest {
        val video = resolved(OutputType.VIDEO)
        every { platformResolver.resolve(URL, OutputType.VIDEO) } returns video
        every { platformResolver.resolve(URL, OutputType.AUDIO) } returns resolved(OutputType.AUDIO)
        coEvery { ytDlpService.extractCatalogMetadata(video) } returns metadata(
            formats = listOf(
                videoFormat("v720", 720, 200_000_000),
                audioFormat("a1", 20_000_000),
            ),
        )

        planner.plan(URL)
        planner.plan(URL)

        coVerify(exactly = 1) { ytDlpService.extractCatalogMetadata(video) }
    }

    @Test
    fun omitsUnavailableResolutionAndMarksOversizedOriginalUnavailable() = runTest {
        val video = resolved(OutputType.VIDEO)
        every { platformResolver.resolve(URL, OutputType.VIDEO) } returns video
        every { platformResolver.resolve(URL, OutputType.AUDIO) } returns resolved(OutputType.AUDIO)
        coEvery { ytDlpService.extractCatalogMetadata(video) } returns metadata(
            formats = listOf(
                videoFormat("v1440", 1440, 1_990_000_000),
                videoFormat("v720", 720, 200_000_000),
                audioFormat("a1", 20_000_000),
            ),
        )

        val actual = planner.plan(URL)

        assertEquals(listOf("video_original", "video_720", "audio", "cover"), actual.options.map { it.key })
        val original = actual.options.first()
        assertFalse(original.available)
        assertEquals("Размер превышает лимит Telegram", original.unavailableReason)
    }

    @Test
    fun usesApproximateBitrateSizeWhenFormatSizeIsMissing() = runTest {
        val video = resolved(OutputType.VIDEO)
        every { platformResolver.resolve(URL, OutputType.VIDEO) } returns video
        every { platformResolver.resolve(URL, OutputType.AUDIO) } returns resolved(OutputType.AUDIO)
        coEvery { ytDlpService.extractCatalogMetadata(video) } returns metadata(
            formats = listOf(
                videoFormat("v720", 720, size = null, bitrate = 1_000),
                audioFormat("a1", size = null, bitrate = 100),
            ),
        )

        val option = planner.plan(URL).options.first { it.key == "video_original" }

        assertEquals(16_500_000, option.sizeBytes)
        assertTrue(option.approximateSize)
    }

    @Test
    fun loadsInstagramCatalogThroughRegisteredStrategy() = runTest {
        val video = resolved(OutputType.VIDEO).withInstagramStrategy()
        val audio = resolved(OutputType.AUDIO).withInstagramStrategy()
        val catalog = metadata(
            formats = listOf(
                videoFormat("v720", 720, 200_000_000),
                audioFormat("a1", 20_000_000),
            ),
        )
        val session: PreparedDownloadSession = mockk {
            every { metadata } returns catalog
        }
        every { platformResolver.resolve(URL, OutputType.VIDEO) } returns video
        every { platformResolver.resolve(URL, OutputType.AUDIO) } returns audio
        coEvery { instagramExecutor.prepareCatalog(video) } returns DownloadPreparation.Ready(session)

        val actual = planner.plan(URL)

        assertEquals(DownloadStrategy.INSTAGRAM_EMBED, actual.options.first().spec.strategy)
        coVerify(exactly = 1) { instagramExecutor.prepareCatalog(video) }
        coVerify(exactly = 0) { ytDlpService.extractCatalogMetadata(any()) }
    }

    private fun resolved(outputType: OutputType): DownloadSpec {
        val preset = if (outputType == OutputType.AUDIO) "youtube_audio" else "youtube_h264_mobile_2gb"
        return DownloadSpec(
            originalUrl = URL,
            normalizedUrl = URL,
            cacheKey = "youtube:video:id:${outputType.dbValue}:$preset",
            outputType = outputType,
            strategy = DownloadStrategy.YOUTUBE_YT_DLP,
            formatSelector = if (outputType == OutputType.AUDIO) "ba" else "best",
            extraArgs = if (outputType == OutputType.AUDIO) {
                listOf("-x", "--audio-format", "mp3")
            } else {
                listOf("--merge-output-format", "mp4")
            },
            presetName = preset,
        )
    }

    private fun DownloadSpec.withInstagramStrategy(): DownloadSpec {
        return copy(
            strategy = DownloadStrategy.INSTAGRAM_EMBED,
            presetName = if (outputType == OutputType.AUDIO) {
                "instagram_audio"
            } else {
                "instagram_mobile_video"
            },
        )
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
            formatNote = null,
            ext = if (height == null) "m4a" else "mp4",
            width = height?.times(16)?.div(9),
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
            id = "id",
            title = "Title",
            extractor = "youtube",
            webpageUrl = URL,
            thumbnail = "https://i.ytimg.com/vi/id/maxresdefault.jpg",
            duration = BigDecimal.valueOf(120),
            ext = null,
            width = null,
            height = null,
            fps = null,
            filesize = null,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = null,
            format = null,
            track = null,
            artist = null,
            creator = null,
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
