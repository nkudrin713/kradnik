package com.nkudrin713.kradnik.analytics

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DownloadAnalyticsTest {
    private val analyticsEventService: AnalyticsEventService = mockk()
    private val analytics = DownloadAnalytics(analyticsEventService)

    @BeforeEach
    fun setUp() {
        every { analyticsEventService.record(any()) } just Runs
    }

    @Test
    fun `records download requested event`() {
        val commandSlot = slot<RecordAnalyticsEventCommand>()
        val command = CreateDownloadJobCommand(
            telegramUserId = 100,
            telegramChatId = 200,
            spec = DownloadSpec(
                originalUrl = "https://youtu.be/source",
                normalizedUrl = "https://youtu.be/source",
                cacheKey = "youtube:video:id:video:preset",
                outputType = OutputType.VIDEO,
                strategy = DownloadStrategy.YOUTUBE_YT_DLP,
                presetName = "youtube_mobile_video",
                formatSelector = "best",
            ),
        )

        analytics.recordDownloadRequested(
            command = command,
            job = job(),
        )

        verify { analyticsEventService.record(capture(commandSlot)) }
        commandSlot.captured.eventType shouldBe AnalyticsEventType.DOWNLOAD_REQUESTED
        commandSlot.captured.jobId shouldBe 42
        commandSlot.captured.telegramUserId shouldBe 100
        commandSlot.captured.telegramChatId shouldBe 200
        commandSlot.captured.outputType shouldBe OutputType.VIDEO
        commandSlot.captured.cacheKey shouldBe "youtube:video:id:video:preset"
    }

    @Test
    fun `records telegram cache hit and miss events`() {
        val commands = mutableListOf<RecordAnalyticsEventCommand>()
        every { analyticsEventService.record(capture(commands)) } just Runs

        analytics.recordTelegramCacheLookup(
            job = job(),
            cachedJob = job(id = 10),
        )
        analytics.recordTelegramCacheLookup(
            job = job(),
            cachedJob = null,
        )

        commands[0].eventType shouldBe AnalyticsEventType.TELEGRAM_CACHE_HIT
        commands[0].success shouldBe true
        commands[0].properties shouldBe mapOf("cachedJobId" to 10L)
        commands[1].eventType shouldBe AnalyticsEventType.TELEGRAM_CACHE_MISS
        commands[1].success shouldBe false
    }

    @Test
    fun `records rejected preflight event`() {
        val commandSlot = slot<RecordAnalyticsEventCommand>()

        analytics.recordPreflightDecision(
            spec = DownloadSpec(
                originalUrl = "https://youtu.be/source",
                normalizedUrl = "https://youtu.be/source",
                cacheKey = "youtube:audio",
                outputType = OutputType.AUDIO,
                strategy = DownloadStrategy.YOUTUBE_YT_DLP,
                formatSelector = "ba/bestaudio",
                presetName = "youtube_audio",
            ),
            metadata = metadata(),
            decision = DownloadPreflightDecision.Rejected("too large"),
        )

        verify { analyticsEventService.record(capture(commandSlot)) }
        commandSlot.captured.eventType shouldBe AnalyticsEventType.PREFLIGHT_REJECTED
        commandSlot.captured.platform shouldBe "youtube"
        commandSlot.captured.outputType shouldBe OutputType.AUDIO
        commandSlot.captured.sourceDurationSeconds shouldBe 120
        commandSlot.captured.success shouldBe false
        commandSlot.captured.errorCode shouldBe "too_large"
        commandSlot.captured.properties["reason"] shouldBe "too large"
    }

    @Test
    fun `records completed lifecycle event`() {
        val commandSlot = slot<RecordAnalyticsEventCommand>()

        analytics.recordDownloadCompleted(
            job = job(),
            result = DownloadedFileResult(
                telegramFileId = "file-id",
                telegramFileSize = 900,
                downloadedFileSize = 1_000,
            ),
        )

        verify { analyticsEventService.record(capture(commandSlot)) }
        commandSlot.captured.eventType shouldBe AnalyticsEventType.DOWNLOAD_COMPLETED
        commandSlot.captured.downloadedFileSize shouldBe 1_000
        commandSlot.captured.telegramFileSize shouldBe 900
        commandSlot.captured.success shouldBe true
    }

    @Test
    fun `records authentication failure lifecycle event`() {
        val commandSlot = slot<RecordAnalyticsEventCommand>()

        analytics.recordAuthenticationRequiredFailure(
            job = job(),
            errorMessage = "yt-dlp authentication required",
        )

        verify { analyticsEventService.record(capture(commandSlot)) }
        commandSlot.captured.eventType shouldBe AnalyticsEventType.DOWNLOAD_FAILED
        commandSlot.captured.success shouldBe false
        commandSlot.captured.errorCode shouldBe "authentication_required"
        commandSlot.captured.properties["errorMessage"] shouldBe "yt-dlp authentication required"
    }

    private fun job(id: Long = 42): DownloadJob {
        return DownloadJob(
            id = id,
            telegramUserId = 100,
            telegramChatId = 200,
            originalUrl = "https://youtu.be/source",
            normalizedUrl = "https://youtu.be/source",
            cacheKey = "youtube:video:id:audio:preset",
            outputType = OutputType.AUDIO,
            sourceExtractor = "youtube",
            sourceDurationSeconds = 120,
            downloadedFileSize = 1_000,
            telegramFileSize = 900,
        )
    }

    private fun metadata(): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = "id",
            title = "title",
            extractor = "youtube",
            webpageUrl = "https://youtu.be/source",
            thumbnail = null,
            duration = BigDecimal.valueOf(120),
            ext = "mp4",
            width = 1280,
            height = 720,
            fps = null,
            filesize = 1_000,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = null,
            format = null,
            track = null,
            artist = null,
            creator = null,
            uploader = null,
            channel = null,
            requestedFormats = null,
        )
    }
}
