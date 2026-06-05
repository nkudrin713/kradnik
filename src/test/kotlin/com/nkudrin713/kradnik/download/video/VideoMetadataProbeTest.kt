package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class VideoMetadataProbeTest {
    private val processRunner: ProcessRunner = mockk()
    private val probe = VideoMetadataProbe(processRunner)

    @Test
    fun parsesFfprobeOutput() = runTest {
        coEvery { processRunner.run(any()) } returns result(
            output = """
                1080
                1920
                1:1
                9:16
            """.trimIndent()
        )

        val actual = probe.probe(Path.of("video.mp4"))

        assertEquals(1080, actual.width)
        assertEquals(1920, actual.height)
        assertEquals("1:1", actual.sampleAspectRatio)
        assertEquals("9:16", actual.displayAspectRatio)
        assertEquals(true, actual.isVertical)
    }

    @Test
    fun buildsExpectedFfprobeCommand() = runTest {
        coEvery { processRunner.run(any()) } returns result("1080\n1920\n1:1\n9:16")

        probe.probe(Path.of("video.mp4"))

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals("ffprobe", command.executable)
        assertEquals(null, command.workingDir)
        assertEquals(true, command.args.contains("-select_streams"))
        assertEquals(true, command.args.contains("v:0"))
        assertEquals(true, command.args.contains("video.mp4"))
    }

    @Test
    fun throwsOnInvalidOutput() = runTest {
        coEvery { processRunner.run(any()) } returns result("1080")

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun throwsOnFailedProcess() = runTest {
        coEvery { processRunner.run(any()) } returns result(
            output = "ffprobe error",
            exitCode = 1,
        )

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun throwsOnTimedOutProcess() = runTest {
        coEvery { processRunner.run(any()) } returns result(
            output = "ffprobe timeout",
            exitCode = null,
            timedOut = true,
        )

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun throwsOnInvalidHeight() = runTest {
        coEvery { processRunner.run(any()) } returns result("1080\ninvalid\n1:1\n9:16")

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun throwsOnInvalidWidth() = runTest {
        coEvery { processRunner.run(any()) } returns result("invalid\n1080\n1:1\n9:16")

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun detectsHorizontalVideo() = runTest {
        coEvery { processRunner.run(any()) } returns result("1920\n1080\n1:1\n16:9")

        val actual = probe.probe(Path.of("video.mp4"))

        assertEquals(false, actual.isVertical)
    }

    private fun result(
        output: String,
        exitCode: Int? = 0,
        timedOut: Boolean = false,
    ): ProcessExecutionResult {
        return ProcessExecutionResult(
            timedOut = timedOut,
            exitCode = exitCode,
            output = output,
            duration = 1.seconds,
        )
    }
}
