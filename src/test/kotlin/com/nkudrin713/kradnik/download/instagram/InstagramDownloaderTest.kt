package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.DownloadPreparation
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InstagramDownloaderTest {
    private val embedDownloader: InstagramEmbedDownloader = mockk()
    private val rateLimiter: InstagramRateLimiter = mockk()
    private val ytDlpService: YtDlpService = mockk()
    private val downloader = InstagramDownloader(embedDownloader, rateLimiter, ytDlpService)

    @Test
    fun returnsNotReadyWithoutCallingInstagramWhenPermitIsDeferred() = runTest {
        val request = request()
        val retryAt = Instant.parse("2026-07-15T10:00:30Z")
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns InstagramRateLimitDecision.Deferred(retryAt)

        val result = assertIs<DownloadPreparation.NotReady>(downloader.prepare(request))

        assertEquals(retryAt, result.retryAt)
        coVerify(exactly = 0) { embedDownloader.prepare(any()) }
    }

    @Test
    fun preparesAndDownloadsInstagramVideo() = runTest {
        val request = request()
        val prepared = preparedDownload()
        val outputDir = Path.of("/tmp/output")
        val downloaded = DownloadedFile(outputDir.resolve("video.mp4"), 100)
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns InstagramRateLimitDecision.Granted(PERMIT)
        coEvery { embedDownloader.prepare(request) } returns prepared
        every { rateLimiter.recordSuccess(PERMIT) } returns Unit
        coEvery { embedDownloader.download(prepared, outputDir) } returns downloaded

        val result = assertIs<DownloadPreparation.Ready>(downloader.prepare(request))

        assertEquals(prepared.metadata, result.session.metadata)
        assertEquals(downloaded, result.session.download(request, outputDir))
        verify(exactly = 1) { rateLimiter.recordSuccess(PERMIT) }
        coVerify(exactly = 0) { ytDlpService.download(any(), any()) }
    }

    @Test
    fun downloadsVideoWithYtDlpWhenEmbedHasNoMediaUrl() = runTest {
        val request = request()
        val prepared = preparedDownload(mediaUri = null)
        val outputDir = Path.of("/tmp/output")
        val downloaded = DownloadedFile(outputDir.resolve("video.mp4"), 100)
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns InstagramRateLimitDecision.Granted(PERMIT)
        coEvery { embedDownloader.prepare(request) } returns prepared
        every { rateLimiter.recordSuccess(PERMIT) } returns Unit
        coEvery { ytDlpService.download(request, outputDir) } returns downloaded

        val result = assertIs<DownloadPreparation.Ready>(downloader.prepare(request))

        assertEquals(prepared.metadata, result.session.metadata)
        assertEquals(downloaded, result.session.download(request, outputDir))
        coVerify(exactly = 0) { embedDownloader.download(any(), any()) }
        coVerify(exactly = 1) { ytDlpService.download(request, outputDir) }
    }

    @Test
    fun downloadsInstagramAudioWithYtDlp() = runTest {
        val request = request(outputType = OutputType.AUDIO)
        val prepared = preparedDownload()
        val outputDir = Path.of("/tmp/output")
        val downloaded = DownloadedFile(outputDir.resolve("audio.mp3"), 100)
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns InstagramRateLimitDecision.Granted(PERMIT)
        coEvery { embedDownloader.prepare(request) } returns prepared
        every { rateLimiter.recordSuccess(PERMIT) } returns Unit
        coEvery { ytDlpService.download(request, outputDir) } returns downloaded

        val result = assertIs<DownloadPreparation.Ready>(downloader.prepare(request))

        assertEquals(downloaded, result.session.download(request, outputDir))
        coVerify(exactly = 0) { embedDownloader.download(any(), any()) }
        coVerify(exactly = 1) { ytDlpService.download(request, outputDir) }
    }

    @Test
    fun startsCooldownOnRateLimitedResponse() = runTest {
        val request = request()
        val retryAt = Instant.parse("2026-07-15T10:30:00Z")
        val error = InstagramHttpException(
            stage = InstagramRequestStage.EMBED,
            statusCode = 429,
            retryAfter = Duration.ofMinutes(10),
        )
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns InstagramRateLimitDecision.Granted(PERMIT)
        coEvery { embedDownloader.prepare(request) } throws error
        every { rateLimiter.recordThrottle(PERMIT, Duration.ofMinutes(10)) } returns retryAt

        val result = assertIs<DownloadPreparation.RetryableFailure>(downloader.prepare(request))

        assertEquals(retryAt, result.retryAt)
        verify { rateLimiter.recordThrottle(PERMIT, Duration.ofMinutes(10)) }
    }

    @Test
    fun reportsUnavailableInstagramContentWithoutRetry() = runTest {
        val request = request()
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns InstagramRateLimitDecision.Granted(PERMIT)
        coEvery { embedDownloader.prepare(request) } throws InstagramContentUnavailableException()

        val result = assertIs<DownloadPreparation.SourceUnavailable>(downloader.prepare(request))

        assertEquals("Instagram content is unavailable without authentication", result.reason)
        verify(exactly = 0) { rateLimiter.recordThrottle(any(), any()) }
    }

    @Test
    fun rejectsUnsupportedInstagramRequestBeforeLimiter() = runTest {
        val request = request(url = "https://www.instagram.com/stories/user/123/")
        every { embedDownloader.supports(request) } returns false

        assertIs<DownloadPreparation.TerminalFailure>(downloader.prepare(request))

        verify(exactly = 0) { rateLimiter.acquire() }
    }

    private fun request(
        outputType: OutputType = OutputType.VIDEO,
        url: String = "https://www.instagram.com/reel/ABC_123/",
    ): DownloadSpec {
        return DownloadSpec(
            originalUrl = url,
            normalizedUrl = url,
            cacheKey = "instagram",
            outputType = outputType,
            platform = DownloadPlatform.INSTAGRAM,
            formatSelector = "format",
            presetName = "instagram",
        )
    }

    private fun preparedDownload(
        mediaUri: URI? = URI.create("https://scontent-test.cdninstagram.com/video.mp4"),
    ): InstagramPreparedDownload {
        return InstagramPreparedDownload(
            shortcode = "ABC_123",
            mediaUri = mediaUri,
            metadata = mockk<YtDlpMetadataDto>(),
        )
    }

    private companion object {
        val PERMIT: Instant = Instant.parse("2026-07-15T10:00:00Z")
    }
}
