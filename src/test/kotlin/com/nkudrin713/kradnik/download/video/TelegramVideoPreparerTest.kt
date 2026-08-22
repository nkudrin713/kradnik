package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.download.domain.DownloadedFile
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
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TelegramVideoPreparerTest {
    private val processRunner: ProcessRunner = mockk()
    private val videoMetadataProbe: VideoMetadataProbe = mockk()
    private val preparer = TelegramVideoPreparer(
        processRunner = processRunner,
        videoMetadataProbe = videoMetadataProbe,
        videoPolicy = TelegramVideoPolicy(
            TelegramUploadLimits(TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES)
        ),
    )

    @Test
    fun returnsSmallCompatibleFileWithoutTranscoding(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES)
        coEvery { videoMetadataProbe.probe(file.file) } returns verticalMetadata()

        val actual = preparer.prepare(file, tempDir, jobId = 1)

        assertEquals(file, actual)
        coVerify(exactly = 0) { processRunner.run(any()) }
    }

    @Test
    fun transcodesSmallVp9Video(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, 1_000)
        val preparedFile = tempDir.resolve("telegram-video.mp4")
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata().copy(
            videoCodec = "vp9",
            codecTag = "vp09",
        )
        coEvery { processRunner.run(any()) } answers {
            Path.of(firstArg<Command>().args.last()).writeText("transcoded")
            processResult()
        }
        coEvery { videoMetadataProbe.probe(preparedFile) } returns verticalMetadata()

        val actual = preparer.prepare(file, tempDir, jobId = 1)

        assertEquals(preparedFile, actual.file)
        assertEquals("transcoded".length.toLong(), actual.sizeBytes)

        val command = slot<Command>()
        coVerify { processRunner.run(capture(command)) }
        assertEquals(true, command.captured.args.containsAll(listOf("-c:v", "libx264")))
        assertEquals(true, command.captured.args.containsAll(listOf("-c:a", "aac")))
        assertEquals(true, command.captured.args.containsAll(listOf("-pix_fmt", "yuv420p")))
        assertEquals(true, command.captured.args.containsAll(listOf("-movflags", "+faststart")))
    }

    @Test
    fun rejectsLargeHorizontalVideo(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1)
        coEvery { videoMetadataProbe.probe(file.file) } returns horizontalMetadata()

        assertFailsWith<VideoTooLargeException> {
            preparer.prepare(file, tempDir, jobId = 1)
        }
        coVerify(exactly = 0) { processRunner.run(any()) }
    }

    @Test
    fun compressesLargeVerticalVideo(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1)
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata()
        coEvery { processRunner.run(any()) } answers {
            Path.of(firstArg<Command>().args.last()).writeText("compressed")
            processResult()
        }
        coEvery { videoMetadataProbe.probe(tempDir.resolve("telegram-video.mp4")) } returns verticalMetadata()

        val actual = preparer.prepare(file, tempDir, jobId = 1)

        assertEquals(tempDir.resolve("telegram-video.mp4"), actual.file)
        assertEquals("compressed".length.toLong(), actual.sizeBytes)
        coVerify { processRunner.run(any()) }
    }

    @Test
    fun failsWhenTranscodingProcessFails(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, 1_000)
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata().copy(videoCodec = "vp9")
        coEvery { processRunner.run(any()) } returns processResult(exitCode = 1, output = "ffmpeg error")

        assertFailsWith<VideoPrepareException> {
            preparer.prepare(file, tempDir, jobId = 1)
        }
    }

    @Test
    fun failsWhenTranscodingProcessTimesOut(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, 1_000)
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata().copy(videoCodec = "vp9")
        coEvery { processRunner.run(any()) } returns ProcessExecutionResult(
            timedOut = true,
            exitCode = null,
            stderr = "ffmpeg timeout",
            duration = 1.seconds,
        )

        assertFailsWith<VideoPrepareException> {
            preparer.prepare(file, tempDir, jobId = 1)
        }
    }

    @Test
    fun failsWhenPreparedVideoIsStillTooLarge(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1)
        val preparedFile = tempDir.resolve("telegram-video.mp4")
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata()
        coEvery { processRunner.run(any()) } answers {
            RandomAccessFile(Path.of(firstArg<Command>().args.last()).toFile(), "rw").use { output ->
                output.setLength(TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1)
            }
            processResult()
        }
        coEvery { videoMetadataProbe.probe(preparedFile) } returns verticalMetadata()

        assertFailsWith<VideoTooLargeException> {
            preparer.prepare(file, tempDir, jobId = 1)
        }
    }

    @Test
    fun failsWhenPreparedVideoStillViolatesPolicy(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, 1_000)
        val preparedFile = tempDir.resolve("telegram-video.mp4")
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata().copy(videoCodec = "vp9")
        coEvery { processRunner.run(any()) } answers {
            Path.of(firstArg<Command>().args.last()).writeText("transcoded")
            processResult()
        }
        coEvery { videoMetadataProbe.probe(preparedFile) } returns verticalMetadata().copy(videoCodec = "vp9")

        assertFailsWith<VideoPrepareException> {
            preparer.prepare(file, tempDir, jobId = 1)
        }
    }

    private fun verticalMetadata(): VideoMetadata {
        return VideoMetadata(
            width = 1080,
            height = 1920,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "9:16",
            containerFormat = "mov,mp4,m4a,3gp,3g2,mj2",
            videoCodec = "h264",
            audioCodec = "aac",
            codecTag = "avc1",
            pixelFormat = "yuv420p",
        )
    }

    private fun horizontalMetadata(): VideoMetadata {
        return verticalMetadata().copy(
            width = 1920,
            height = 1080,
            displayAspectRatio = "16:9",
        )
    }

    private fun processResult(
        exitCode: Int? = 0,
        output: String = "",
    ): ProcessExecutionResult {
        return ProcessExecutionResult(
            timedOut = false,
            exitCode = exitCode,
            stderr = output,
            duration = 1.seconds,
        )
    }
}
