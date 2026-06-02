package com.nkudrin713.kradnik.ytdlp.client

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
    fun success() = runTest {
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
        val expectedResult = ProcessExecutionResult(
            output = output,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val url = "url"
        val actual = service.extractMetadata(url)

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
    fun ignoresUnknownMetadataFields() = runTest {
        val expectedResult = ProcessExecutionResult(
            output = """{"id":"video-id","title":"Test video","formats":[{"format_id":"1"}]}""",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val actual = service.extractMetadata("https://example.com")

        assertEquals("video-id", actual.id)
        assertEquals("Test video", actual.title)
    }

    @Test
    fun timeout() = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("timed out"))
    }

    @Test
    fun failure() = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "yt-dlp error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("failed"))
        assertTrue(exception.message!!.contains("yt-dlp error"))
    }

    @Test
    fun emptyOutput() = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("empty output"))
    }

    @Test
    fun downloadAudioSuccess(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        val expectedResult = ProcessExecutionResult(
            output = file.absolutePathString(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val actual = downloadAudio(tempDir)

        assertEquals(file, actual.file)
        assertEquals("mp3", actual.ext)
        assertEquals(file.fileSize(), actual.sizeBytes)
        assertTrue(actual.args.contains("https://example.com"))
    }

    @Test
    fun downloadAudioTimeout(@TempDir tempDir: Path) = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            downloadAudio(tempDir)
        }

        assertTrue(exception.message!!.contains("timed out"))
    }

    @Test
    fun downloadAudioFailure(@TempDir tempDir: Path) = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "yt-dlp error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            downloadAudio(tempDir)
        }

        assertTrue(exception.message!!.contains("failed"))
        assertTrue(exception.message!!.contains("yt-dlp error"))
    }

    @Test
    fun downloadAudioMissingFilepath(@TempDir tempDir: Path) = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            downloadAudio(tempDir)
        }

        assertTrue(exception.message!!.contains("did not print final filepath"))
    }

    @Test
    fun downloadAudioFileNotFound(@TempDir tempDir: Path) = runTest {
        val missingFile = tempDir.resolve("missing.mp3")
        val expectedResult = ProcessExecutionResult(
            output = missingFile.absolutePathString(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val exception = assertFailsWith<YtDlpException> {
            downloadAudio(tempDir)
        }

        assertTrue(exception.message!!.contains("file not found"))
    }

    @Test
    fun downloadAudioUsesLastOutputLineAsFilepath(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        val expectedResult = ProcessExecutionResult(
            output = """
                log line
                
                ${file.absolutePathString()}
            """.trimIndent(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val actual = downloadAudio(tempDir)

        assertEquals(file, actual.file)
    }

    @Test
    fun downloadAudioBuildsExpectedCommand(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        val expectedResult = ProcessExecutionResult(
            output = file.absolutePathString(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        downloadAudio(tempDir)

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals("yt-dlp", command.executable)
        assertEquals(tempDir, command.workingDir)
        assertTrue(command.args.contains("-f"))
        assertTrue(command.args.contains("bestaudio/best"))
        assertTrue(command.args.contains("-x"))
        assertTrue(command.args.contains("--audio-format"))
        assertTrue(command.args.contains("mp3"))
        assertTrue(command.args.contains("--audio-quality"))
        assertTrue(command.args.contains("128K"))
        assertTrue(command.args.contains("--print"))
        assertTrue(command.args.contains("after_move:filepath"))
        assertTrue(command.args.contains("https://example.com"))
    }

    @Test
    fun downloadVideoBuildsExpectedCommand(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        file.writeText("video")
        val expectedResult = ProcessExecutionResult(
            output = file.absolutePathString(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        coEvery { processRunner.run(any()) } returns expectedResult

        val actual = service.downloadVideo("https://example.com", tempDir, 28, "96k")

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals(file, actual.file)
        assertEquals("mp4", actual.ext)
        assertEquals("yt-dlp", command.executable)
        assertEquals(tempDir, command.workingDir)
        assertTrue(command.args.contains("-f"))
        assertTrue(command.args.contains("bv*+ba/b"))
        assertTrue(command.args.contains("--recode-video"))
        assertTrue(command.args.contains("mp4"))
        assertTrue(command.args.contains("--postprocessor-args"))
        assertTrue(command.args.contains("VideoConvertor:-c:v libx264 -preset slow -crf 28 -c:a aac -b:a 96k -movflags +faststart"))
        assertTrue(command.args.contains("--print"))
        assertTrue(command.args.contains("after_move:filepath"))
        assertTrue(command.args.contains("https://example.com"))
    }

    private suspend fun downloadAudio(tempDir: Path) =
        service.downloadAudio("https://example.com", tempDir, "128K")

}
