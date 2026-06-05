package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TelegramVideoPreparerTest {
    private val processRunner: ProcessRunner = mockk()
    private val videoMetadataProbe: VideoMetadataProbe = mockk()
    private val preparer = TelegramVideoPreparer(processRunner, videoMetadataProbe)

    @Test
    fun returnsSmallFileWithoutCompression(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), TelegramUploadLimits.MAX_UPLOAD_BYTES)

        val actual = preparer.prepare(file, tempDir, jobId = 1)

        assertEquals(file, actual)
    }

    @Test
    fun rejectsLargeHorizontalVideo(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), TelegramUploadLimits.MAX_UPLOAD_BYTES + 1)
        coEvery { videoMetadataProbe.probe(file.file) } returns horizontalMetadata()

        assertFailsWith<VideoTooLargeException> {
            preparer.prepare(file, tempDir, jobId = 1)
        }
    }

    @Test
    fun compressesLargeVerticalVideo(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, TelegramUploadLimits.MAX_UPLOAD_BYTES + 1)
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
    fun failsWhenCompressionProcessFails(@TempDir tempDir: Path) = runTest {
        val source = tempDir.resolve("source.mp4")
        val file = DownloadedFile(source, TelegramUploadLimits.MAX_UPLOAD_BYTES + 1)
        coEvery { videoMetadataProbe.probe(source) } returns verticalMetadata()
        coEvery { processRunner.run(any()) } returns processResult(exitCode = 1, output = "ffmpeg error")

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
        )
    }

    private fun horizontalMetadata(): VideoMetadata {
        return VideoMetadata(
            width = 1920,
            height = 1080,
            sampleAspectRatio = "1:1",
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
            output = output,
            duration = 1.seconds,
        )
    }
}
