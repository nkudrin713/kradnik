package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.ratelimit.RateLimitDecision
import com.nkudrin713.kradnik.download.request.DownloadRequest
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

class InstagramDownloadExecutorTest {
    private val embedDownloader: InstagramEmbedDownloader = mockk()
    private val rateLimiter: InstagramRateLimiter = mockk()
    private val executor = InstagramDownloadExecutor(embedDownloader, rateLimiter)

    @Test
    fun returnsNotReadyWithoutCallingInstagramWhenPermitIsDeferred() = runTest {
        val request = request()
        val retryAt = Instant.parse("2026-07-15T10:00:30Z")
        every { embedDownloader.supports(request) } returns true
        every { rateLimiter.acquire() } returns RateLimitDecision.Deferred(retryAt)

        val result = assertIs<DownloadPreparation.NotReady>(executor.prepare(request))

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
        every { rateLimiter.acquire() } returns RateLimitDecision.Granted
        coEvery { embedDownloader.prepare(request) } returns prepared
        every { rateLimiter.recordSuccess() } returns Unit
        coEvery { embedDownloader.download(prepared, outputDir) } returns downloaded

        val result = assertIs<DownloadPreparation.Ready>(executor.prepare(request))

        assertEquals(prepared.metadata, result.session.metadata)
        assertEquals(downloaded, result.session.download(request, outputDir))
        verify(exactly = 1) { rateLimiter.recordSuccess() }
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
        every { rateLimiter.acquire() } returns RateLimitDecision.Granted
        coEvery { embedDownloader.prepare(request) } throws error
        every { rateLimiter.recordThrottle(Duration.ofMinutes(10)) } returns retryAt

        val result = assertIs<DownloadPreparation.RetryableFailure>(executor.prepare(request))

        assertEquals(retryAt, result.retryAt)
        verify { rateLimiter.recordThrottle(Duration.ofMinutes(10)) }
    }

    @Test
    fun rejectsUnsupportedInstagramRequestBeforeLimiter() = runTest {
        val request = request(outputType = OutputType.AUDIO)
        every { embedDownloader.supports(request) } returns false

        assertIs<DownloadPreparation.TerminalFailure>(executor.prepare(request))

        verify(exactly = 0) { rateLimiter.acquire() }
    }

    private fun request(outputType: OutputType = OutputType.VIDEO): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://www.instagram.com/reel/ABC_123/",
            normalizedUrl = "https://www.instagram.com/reel/ABC_123/",
            outputType = outputType,
            formatSelector = "format",
            presetName = "instagram",
        )
    }

    private fun preparedDownload(): InstagramPreparedDownload {
        return InstagramPreparedDownload(
            shortcode = "ABC_123",
            mediaUri = URI.create("https://scontent-test.cdninstagram.com/video.mp4"),
            metadata = mockk<YtDlpMetadataDto>(),
        )
    }
}
