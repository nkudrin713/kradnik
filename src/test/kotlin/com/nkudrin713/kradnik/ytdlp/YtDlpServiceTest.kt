package com.nkudrin713.kradnik.ytdlp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
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

}
