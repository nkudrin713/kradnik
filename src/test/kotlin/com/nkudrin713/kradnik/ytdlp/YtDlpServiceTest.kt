package com.nkudrin713.kradnik.ytdlp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.process.ProcessCommand
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class YtDlpServiceTest {
    private val processRunner: ProcessRunner = mock()
    private val objectMapper = jacksonObjectMapper()

    private val service = YtDlpService(
        processRunner = processRunner,
        objectMapper = objectMapper,
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

        whenever(processRunner.run(any())).thenReturn(expectedResult)

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
    fun timeout() = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

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

        whenever(processRunner.run(any())).thenReturn(expectedResult)

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

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val exception = assertFailsWith<YtDlpException> {
            service.extractMetadata("https://example.com")
        }

        assertTrue(exception.message!!.contains("empty output"))
    }

    @Test
    fun downloadAudioSuccess(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "audio.mp3")
        file.writeText("audio")
        val expectedResult = ProcessExecutionResult(
            output = file.absolutePath,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val actual = service.downloadAudio("https://example.com", tempDir)

        assertEquals(file, actual.file)
        assertEquals("mp3", actual.ext)
        assertEquals(file.length(), actual.sizeBytes)
        assertTrue(actual.args.contains("https://example.com"))
    }

    @Test
    fun downloadAudioTimeout(@TempDir tempDir: File) = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = true,
            exitCode = null,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val exception = assertFailsWith<YtDlpException> {
            service.downloadAudio("https://example.com", tempDir)
        }

        assertTrue(exception.message!!.contains("timed out"))
    }

    @Test
    fun downloadAudioFailure(@TempDir tempDir: File) = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "yt-dlp error",
            timedOut = false,
            exitCode = 1,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val exception = assertFailsWith<YtDlpException> {
            service.downloadAudio("https://example.com", tempDir)
        }

        assertTrue(exception.message!!.contains("failed"))
        assertTrue(exception.message!!.contains("yt-dlp error"))
    }

    @Test
    fun downloadAudioMissingFilepath(@TempDir tempDir: File) = runTest {
        val expectedResult = ProcessExecutionResult(
            output = "",
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val exception = assertFailsWith<YtDlpException> {
            service.downloadAudio("https://example.com", tempDir)
        }

        assertTrue(exception.message!!.contains("did not print final filepath"))
    }

    @Test
    fun downloadAudioFileNotFound(@TempDir tempDir: File) = runTest {
        val missingFile = File(tempDir, "missing.mp3")
        val expectedResult = ProcessExecutionResult(
            output = missingFile.absolutePath,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val exception = assertFailsWith<YtDlpException> {
            service.downloadAudio("https://example.com", tempDir)
        }

        assertTrue(exception.message!!.contains("file not found"))
    }

    @Test
    fun downloadAudioUsesLastOutputLineAsFilepath(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "audio.mp3")
        file.writeText("audio")
        val expectedResult = ProcessExecutionResult(
            output = """
                log line
                
                ${file.absolutePath}
            """.trimIndent(),
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        val actual = service.downloadAudio("https://example.com", tempDir)

        assertEquals(file, actual.file)
    }

    @Test
    fun downloadAudioBuildsExpectedCommand(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "audio.mp3")
        file.writeText("audio")
        val expectedResult = ProcessExecutionResult(
            output = file.absolutePath,
            timedOut = false,
            exitCode = 0,
            duration = 5.seconds
        )

        whenever(processRunner.run(any())).thenReturn(expectedResult)

        service.downloadAudio("https://example.com", tempDir)

        val commandCaptor = argumentCaptor<ProcessCommand>()
        verify(processRunner).run(commandCaptor.capture())

        val command = commandCaptor.firstValue
        assertEquals("yt-dlp", command.executable)
        assertEquals(tempDir, command.workingDir)
        assertTrue(command.args.contains("-f"))
        assertTrue(command.args.contains("bestaudio/best"))
        assertTrue(command.args.contains("-x"))
        assertTrue(command.args.contains("--audio-format"))
        assertTrue(command.args.contains("mp3"))
        assertTrue(command.args.contains("--audio-quality"))
        assertTrue(command.args.contains("0"))
        assertTrue(command.args.contains("--print"))
        assertTrue(command.args.contains("after_move:filepath"))
        assertTrue(command.args.contains("https://example.com"))
    }

}
