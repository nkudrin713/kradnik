package com.nkudrin713.kradnik.ytdlp.client

import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.fileSize
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class YtDlpServiceTest {
    private val processRunner: ProcessRunner = mockk()

    private val service = YtDlpService(
        processRunner = processRunner,
    )
    private val localService = YtDlpService(
        processRunner = processRunner,
        uploadLimits = TelegramUploadLimits(
            maxUploadBytes = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES,
            localMode = true,
        ),
    )
    private val serviceWithProvider = YtDlpService(
        processRunner = processRunner,
        youtubePoTokenProviderUrl = "http://youtube-pot-provider:4416/",
    )

    @Test
    fun extractMetadataParsesMetadata() = runTest {
        val output = """
            {
              "id": "video-id",
              "title": "Test video",
              "extractor": "youtube",
              "duration": 120,
              "ext": "webm",
              "width": 1080,
              "height": 1920,
              "fps": 30,
              "filesize_approx": 42000000,
              "format_id": "399+251"
            }
        """.trimIndent()

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = output,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractMetadata(testRequest())

        assertEquals("video-id", actual.id)
        assertEquals("Test video", actual.title)
        assertEquals(BigDecimal.valueOf(120), actual.duration)
        assertEquals("webm", actual.ext)
        assertEquals(1080, actual.width)
        assertEquals(1920, actual.height)
        assertEquals(BigDecimal.valueOf(30), actual.fps)
        assertEquals(42000000, actual.filesizeApprox)
        assertEquals("399+251", actual.formatId)
    }

    @Test
    fun extractMetadataIgnoresUnknownFields() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video","formats":[{"format_id":"1"}]}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractMetadata(testRequest())

        assertEquals("video-id", actual.id)
        assertEquals("Test video", actual.title)
    }

    @Test
    fun extractCatalogMetadataKeepsFormatsAndDoesNotPreselectFormat() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","formats":[{"format_id":"22","height":720,"filesize":1000}]}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractCatalogMetadata(testRequest())

        assertEquals("22", actual.formats?.single()?.formatId)
        val command = slot<Command>()
        coVerify { processRunner.run(capture(command)) }
        assertFalse(command.captured.args.contains("-f"))
    }

    @Test
    fun extractMetadataIgnoresStderrOnSuccessfulProcess() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video"}""",
            stderr = "runtime warning",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractMetadata(testRequest())

        assertEquals("video-id", actual.id)
    }

    @Test
    fun extractMetadataRejectsTruncatedStdout() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id"}""",
            stdoutTruncated = true,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("capture limit"))
    }

    @Test
    fun extractMetadataCommandTimeout() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("timed out"))
    }

    @Test
    fun extractMetadataCommandFailure() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stderr = "yt-dlp error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("failed"))
        assertTrue(exception.message!!.contains("yt-dlp error"))
    }

    @Test
    fun extractMetadataAuthenticationRequired() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stderr = "ERROR: [Instagram] id: login required. Use --cookies-from-browser or --cookies",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpAuthenticationRequiredException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("authentication required"))
        assertTrue(exception.message!!.contains("--cookies"))
    }

    @Test
    fun downloadAuthenticationRequired(@TempDir tempDir: Path) = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stderr = "ERROR: [Instagram] id: Requested content is not available, rate-limit reached or login required",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpAuthenticationRequiredException> {
            service.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("authentication required"))
    }

    @Test
    fun extractMetadataCommandEmptyOutput() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("empty output"))
    }

    @Test
    fun extractMetadataBuildsExpectedCommand() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video"}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        service.extractMetadata(testRequest())

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals("yt-dlp", command.executable)
        assertEquals(null, command.workingDir)
        assertTrue(command.args.contains("--dump-single-json"))
        assertTrue(command.args.contains("--no-playlist"))
        assertTrue(command.args.contains("--no-warnings"))
        assertTrue(command.args.contains("-f"))
        assertTrue(command.args.contains("bv*+ba/b"))
        assertTrue(command.args.contains("https://example.com"))
    }

    @Test
    fun extractMetadataAddsPoTokenProviderArgsForYouTube() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video"}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        serviceWithProvider.extractMetadata(youtubeRequest())

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }
        assertContainsPoTokenProviderArgs(commandSlot.captured.args)
    }

    @Test
    fun extractMetadataDoesNotAddPoTokenProviderArgsForOtherPlatforms() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video"}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        serviceWithProvider.extractMetadata(testRequest())

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }
        assertFalse(commandSlot.captured.args.contains(YOUTUBE_PLAYER_CLIENT_ARG))
        assertFalse(commandSlot.captured.args.contains(YOUTUBE_PROVIDER_ARG))
    }

    @Test
    fun extractMetadataDoesNotAddPoTokenProviderArgsWhenDisabled() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video"}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        service.extractMetadata(youtubeRequest())

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }
        assertFalse(commandSlot.captured.args.contains(YOUTUBE_PLAYER_CLIENT_ARG))
        assertFalse(commandSlot.captured.args.contains(YOUTUBE_PROVIDER_ARG))
    }

    @Test
    fun extractMetadataSuccess() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """{"id":"video-id","title":"Test video","filesize":1000}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractMetadata(testRequest())

        assertEquals("video-id", actual.id)
        assertEquals("Test video", actual.title)
        assertEquals(1000, actual.filesize)
    }

    @Test
    fun extractMetadataTimeout() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("command timed out"))
    }

    @Test
    fun extractMetadataFailure() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stderr = "inspect error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("command failed"))
        assertTrue(exception.message!!.contains("inspect error"))
    }

    @Test
    fun extractMetadataEmptyOutput() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata(testRequest())
        }

        assertTrue(exception.message!!.contains("empty output"))
    }

    @Test
    fun downloadSuccess(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "KRADNIK_FILEPATH:\"${file.absolutePathString()}\"",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.download(testRequest(), tempDir)

        assertEquals(file, actual.file)
        assertEquals(file.fileSize(), actual.sizeBytes)
    }

    @Test
    fun downloadBuildsExpectedCommand(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "KRADNIK_FILEPATH:\"${file.absolutePathString()}\"",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        localService.download(testRequest(), tempDir)

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals("yt-dlp", command.executable)
        assertEquals(tempDir, command.workingDir)
        assertTrue(command.args.contains("--no-restrict-filenames"))
        assertTrue(command.args.contains("-f"))
        assertTrue(command.args.contains("bv*+ba/b"))
        assertTrue(command.args.contains("--print"))
        assertTrue(command.args.contains("after_move:KRADNIK_FILEPATH:%(filepath)j"))
        assertTrue(
            command.args.windowed(2).contains(
                listOf("--max-filesize", TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES.toString())
            )
        )
        assertEquals(
            TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES * 2,
            command.maxWorkingDirectoryBytes,
        )
        assertTrue(command.args.contains("--merge-output-format"))
        assertTrue(command.args.contains("mp4"))
        assertTrue(command.args.contains("https://example.com"))
    }

    @Test
    fun cloudDownloadKeepsVerticalCompressionSourceUnbounded(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "KRADNIK_FILEPATH:\"${file.absolutePathString()}\"",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        service.download(testRequest(), tempDir)

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }
        assertFalse(commandSlot.captured.args.contains("--max-filesize"))
        assertEquals(null, commandSlot.captured.maxWorkingDirectoryBytes)
    }

    @Test
    fun downloadAddsPoTokenProviderArgsForYouTube(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "KRADNIK_FILEPATH:\"${file.absolutePathString()}\"",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        serviceWithProvider.download(youtubeRequest(), tempDir)

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }
        assertContainsPoTokenProviderArgs(commandSlot.captured.args)
    }

    @Test
    fun downloadUsesLastOutputLineAsFilepath(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = """
                log line
                KRADNIK_FILEPATH:"${file.absolutePathString()}"
                trailing log line
            """.trimIndent(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.download(testRequest(), tempDir)

        assertEquals(file, actual.file)
    }

    @Test
    fun downloadMissingFilepath(@TempDir tempDir: Path) = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            localService.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("did not print final filepath"))
    }

    @Test
    fun downloadFileNotFound(@TempDir tempDir: Path) = runTest {
        val missingFile = tempDir.resolve("missing.mp4")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "KRADNIK_FILEPATH:\"${missingFile.absolutePathString()}\"",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("file not found"))
    }

    @Test
    fun downloadTimeout(@TempDir tempDir: Path) = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("command timed out"))
    }

    @Test
    fun downloadFailsWhenWorkingDirectoryLimitIsExceeded(@TempDir tempDir: Path) = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            timedOut = false,
            exitCode = 137,
            workingDirectoryLimitExceeded = true,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpFileSizeLimitException> {
            localService.download(testRequest(), tempDir)
        }

        assertEquals(TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES, exception.limitBytes)
    }

    @Test
    fun downloadFailure(@TempDir tempDir: Path) = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stderr = "download error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("command failed"))
        assertTrue(exception.message!!.contains("download error"))
    }

    @Test
    fun downloadInvalidFilepathJson(@TempDir tempDir: Path) = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            stdout = "KRADNIK_FILEPATH:not-json",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        assertFailsWith<Exception> {
            service.download(testRequest(), tempDir)
        }
    }

    private fun testRequest(): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://example.com",
            normalizedUrl = "https://example.com",
            outputType = OutputType.VIDEO,
            formatSelector = "bv*+ba/b",
            extraArgs = listOf("--merge-output-format", "mp4"),
            presetName = "test",
        )
    }

    private fun youtubeRequest(): DownloadRequest {
        return testRequest().copy(
            originalUrl = "https://youtube.com/watch?v=video-id",
            normalizedUrl = "https://youtube.com/watch?v=video-id",
            presetName = "youtube_h264_mobile_2gb",
        )
    }

    private fun assertContainsPoTokenProviderArgs(args: List<String>) {
        val expectedArgs = listOf(
            "--extractor-args",
            YOUTUBE_PLAYER_CLIENT_ARG,
            "--extractor-args",
            YOUTUBE_PROVIDER_ARG,
        )
        assertTrue(args.windowed(expectedArgs.size).contains(expectedArgs))
    }

    private companion object {
        private const val YOUTUBE_PLAYER_CLIENT_ARG = "youtube:player_client=mweb"
        private const val YOUTUBE_PROVIDER_ARG =
            "youtubepot-bgutilhttp:base_url=http://youtube-pot-provider:4416"
    }
}
