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
                codec_name=h264
                profile=High
                codec_tag_string=avc1
                width=1080
                height=1920
                pix_fmt=yuv420p
                level=40
                r_frame_rate=30/1
                avg_frame_rate=30000/1001
                sample_aspect_ratio=1:1
                display_aspect_ratio=9:16
                color_space=bt709
                color_transfer=bt709
                color_primaries=bt709
            """.trimIndent()
        )

        val actual = probe.probe(Path.of("video.mp4"))

        assertEquals(1080, actual.width)
        assertEquals(1920, actual.height)
        assertEquals("1:1", actual.sampleAspectRatio)
        assertEquals("9:16", actual.displayAspectRatio)
        assertEquals("h264", actual.codecName)
        assertEquals("avc1", actual.codecTag)
        assertEquals("High", actual.codecProfile)
        assertEquals(40, actual.codecLevel)
        assertEquals("yuv420p", actual.pixelFormat)
        assertEquals("30000/1001", actual.frameRate)
        assertEquals("bt709", actual.colorSpace)
        assertEquals("bt709", actual.colorTransfer)
        assertEquals("bt709", actual.colorPrimaries)
        assertEquals(true, actual.isVertical)
    }

    @Test
    fun ignoresStderrOnSuccessfulProbe() = runTest {
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            timedOut = false,
            exitCode = 0,
            stdout = metadataOutput(),
            stderr = "runtime warning",
            duration = 1.seconds,
        )

        val actual = probe.probe(Path.of("video.mp4"))

        assertEquals(1080, actual.width)
        assertEquals(1920, actual.height)
    }

    @Test
    fun buildsExpectedFfprobeCommand() = runTest {
        coEvery { processRunner.run(any()) } returns result(metadataOutput())

        probe.probe(Path.of("video.mp4"))

        val commandSlot = slot<Command>()
        coVerify { processRunner.run(capture(commandSlot)) }

        val command = commandSlot.captured
        assertEquals("ffprobe", command.executable)
        assertEquals(null, command.workingDir)
        assertEquals(true, command.args.contains("-select_streams"))
        assertEquals(true, command.args.contains("v:0"))
        assertEquals(true, command.args.any { it.contains("codec_name") })
        assertEquals(true, command.args.contains("video.mp4"))
    }

    @Test
    fun throwsOnInvalidOutput() = runTest {
        coEvery { processRunner.run(any()) } returns result("width=1080")

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
        coEvery { processRunner.run(any()) } returns result(
            metadataOutput().replace("height=1920", "height=invalid")
        )

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun throwsOnInvalidWidth() = runTest {
        coEvery { processRunner.run(any()) } returns result(
            metadataOutput().replace("width=1080", "width=invalid")
        )

        assertFailsWith<VideoMetadataProbeException> {
            probe.probe(Path.of("video.mp4"))
        }
    }

    @Test
    fun detectsHorizontalVideo() = runTest {
        coEvery { processRunner.run(any()) } returns result(
            """
                width=1920
                height=1080
                sample_aspect_ratio=1:1
                display_aspect_ratio=16:9
            """.trimIndent()
        )

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
            stdout = if (exitCode == 0) output else "",
            stderr = if (exitCode == 0) "" else output,
            duration = 1.seconds,
        )
    }

    private fun metadataOutput(): String {
        return """
            width=1080
            height=1920
            sample_aspect_ratio=1:1
            display_aspect_ratio=9:16
        """.trimIndent()
    }
}
