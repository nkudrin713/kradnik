package com.nkudrin713.kradnik.ytdlp.client

import com.nkudrin713.kradnik.download.DownloadRequest
import com.nkudrin713.kradnik.download.domain.OutputType
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class YtDlpServiceTest {
    private val processRunner: ProcessRunner = mockk()

    private val service = YtDlpService(
        processRunner = processRunner,
    )

    @Test
    fun extractMetadataSuccess() = runTest {
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
            output = output,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractMetadata("https://example.com")

        assertEquals("video-id", actual.id)
        assertEquals("Test video", actual.title)
        assertEquals(120, actual.duration)
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
            output = """{"id":"video-id","title":"Test video","formats":[{"format_id":"1"}]}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val actual = service.extractMetadata("https://example.com")

        assertEquals("video-id", actual.id)
        assertEquals("Test video", actual.title)
    }

    @Test
    fun extractMetadataTimeout() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            output = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("timed out"))
    }

    @Test
    fun extractMetadataFailure() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            output = "yt-dlp error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("failed"))
        assertTrue(exception.message!!.contains("yt-dlp error"))
    }

    @Test
    fun extractMetadataEmptyOutput() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            output = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("empty output"))
    }

    @Test
    fun downloadSuccess(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            output = file.absolutePathString(),
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
            output = file.absolutePathString(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        service.download(testRequest(), tempDir)

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals("yt-dlp", command.executable)
        assertEquals(tempDir, command.workingDir)
        assertTrue(command.args.contains("-f"))
        assertTrue(command.args.contains("bv*+ba/b"))
        assertTrue(command.args.contains("--print"))
        assertTrue(command.args.contains("after_move:filepath"))
        assertTrue(command.args.contains("--merge-output-format"))
        assertTrue(command.args.contains("mp4"))
        assertTrue(command.args.contains("https://example.com"))
    }

    @Test
    fun downloadUsesLastOutputLineAsFilepath(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            output = """
                log line

                ${file.absolutePathString()}
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
            output = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("did not print final filepath"))
    }

    @Test
    fun downloadFileNotFound(@TempDir tempDir: Path) = runTest {
        val missingFile = tempDir.resolve("missing.mp4")

        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            output = missingFile.absolutePathString(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds,
        )

        val exception = assertFailsWith<YtDlpException> {
            service.download(testRequest(), tempDir)
        }

        assertTrue(exception.message!!.contains("file not found"))
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
}
