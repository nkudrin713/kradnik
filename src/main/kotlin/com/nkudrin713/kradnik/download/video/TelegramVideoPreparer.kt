package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Service
class TelegramVideoPreparer(
    private val processRunner: ProcessRunner,
    private val videoMetadataProbe: VideoMetadataProbe,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun prepare(
        file: DownloadedFile,
        outputDir: Path,
        jobId: Long,
    ): DownloadedFile {
        if (file.sizeBytes <= TelegramUploadLimits.MAX_UPLOAD_BYTES) {
            return file
        }

        val dimensions = videoMetadataProbe.probe(file.file)
        if (!dimensions.isVertical) {
            throw VideoTooLargeException(file.sizeBytes)
        }

        logger.info(
            "JOB[{}] compressing vertical video for Telegram: sourceSizeMb={}, width={}, height={}",
            jobId,
            formatMegabytes(file.sizeBytes),
            dimensions.width,
            dimensions.height,
        )

        val compressedFile = outputDir.resolve("telegram-video.mp4")
        transcodeVertical(file.file, compressedFile)

        val compressedSize = withContext(Dispatchers.IO) {
            Files.size(compressedFile)
        }
        val compressedDimensions = videoMetadataProbe.probe(compressedFile)
        if (compressedSize > TelegramUploadLimits.MAX_UPLOAD_BYTES) {
            throw VideoTooLargeException(compressedSize)
        }

        logger.info(
            "JOB[{}] vertical video compressed: sizeMb={}, width={}, height={}, sar={}, dar={}",
            jobId,
            formatMegabytes(compressedSize),
            compressedDimensions.width,
            compressedDimensions.height,
            compressedDimensions.sampleAspectRatio,
            compressedDimensions.displayAspectRatio,
        )

        return DownloadedFile(
            file = compressedFile,
            sizeBytes = compressedSize,
        )
    }

    private suspend fun transcodeVertical(input: Path, output: Path) {
        val result = processRunner.run(
            FfmpegCommand(
                args = listOf(
                    "-y",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", input.toString(),
                    "-vf", "scale=min(1080\\,iw):-2,setsar=1",
                    "-c:v", "libx264",
                    "-preset", "fast",
                    "-crf", "30",
                    "-profile:v", "main",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-b:a", "96k",
                    "-movflags", "+faststart",
                    output.toString(),
                ),
                timeout = 20.minutes,
            )
        )

        if (result.timedOut || result.exitCode != 0) {
            throw VideoPrepareException("ffmpeg failed: ${result.output.take(500)}")
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}

private data class FfmpegCommand(
    override val args: List<String>,
    override val timeout: Duration,
    override val workingDir: Path? = null,
    override val executable: String = "ffmpeg",
) : Command

class VideoTooLargeException(sizeBytes: Long) :
    RuntimeException(
        "Video is too large for Telegram upload: sizeMb=${
            String.format(Locale.US, "%.2f", sizeBytes / (1024.0 * 1024.0))
        }"
    )

class VideoPrepareException(message: String) : RuntimeException(message)
