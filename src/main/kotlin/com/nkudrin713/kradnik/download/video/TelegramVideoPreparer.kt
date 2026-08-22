package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.download.domain.DownloadedFile
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
    private val videoPolicy: TelegramVideoPolicy,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun prepare(
        file: DownloadedFile,
        outputDir: Path,
        jobId: Long,
    ): DownloadedFile {
        val sourceMetadata = videoMetadataProbe.probe(file.file)
        return when (val decision = videoPolicy.evaluate(sourceMetadata, file.sizeBytes)) {
            TelegramVideoPolicyDecision.Accepted -> file
            TelegramVideoPolicyDecision.RejectedTooLarge -> throw VideoTooLargeException(file.sizeBytes)
            is TelegramVideoPolicyDecision.Transcode -> transcode(
                file = file,
                sourceMetadata = sourceMetadata,
                issues = decision.issues,
                outputDir = outputDir,
                jobId = jobId,
            )
        }
    }

    private suspend fun transcode(
        file: DownloadedFile,
        sourceMetadata: VideoMetadata,
        issues: Set<TelegramVideoIssue>,
        outputDir: Path,
        jobId: Long,
    ): DownloadedFile {
        logger.info(
            "JOB[{}] transcoding video for Telegram: reasons={}, sourceSizeMb={}, container={}, " +
                    "videoCodec={}, audioCodec={}, pixelFormat={}, width={}, height={}",
            jobId,
            issues,
            formatMegabytes(file.sizeBytes),
            sourceMetadata.containerFormat,
            sourceMetadata.videoCodec,
            sourceMetadata.audioCodec,
            sourceMetadata.pixelFormat,
            sourceMetadata.width,
            sourceMetadata.height,
        )

        val preparedFile = outputDir.resolve("telegram-video.mp4")
        transcodeForTelegram(file.file, preparedFile)

        val preparedSize = withContext(Dispatchers.IO) {
            Files.size(preparedFile)
        }
        val preparedMetadata = videoMetadataProbe.probe(preparedFile)
        when (val decision = videoPolicy.evaluate(preparedMetadata, preparedSize)) {
            TelegramVideoPolicyDecision.Accepted -> Unit
            TelegramVideoPolicyDecision.RejectedTooLarge -> throw VideoTooLargeException(preparedSize)
            is TelegramVideoPolicyDecision.Transcode -> {
                if (TelegramVideoIssue.FILE_SIZE in decision.issues) {
                    throw VideoTooLargeException(preparedSize)
                }
                throw VideoPrepareException(
                    "Prepared video violates Telegram policy: issues=${decision.issues}"
                )
            }
        }

        logger.info(
            "JOB[{}] video prepared for Telegram: sizeMb={}, container={}, videoCodec={}, " +
                    "audioCodec={}, pixelFormat={}, width={}, height={}",
            jobId,
            formatMegabytes(preparedSize),
            preparedMetadata.containerFormat,
            preparedMetadata.videoCodec,
            preparedMetadata.audioCodec,
            preparedMetadata.pixelFormat,
            preparedMetadata.width,
            preparedMetadata.height,
        )

        return DownloadedFile(
            file = preparedFile,
            sizeBytes = preparedSize,
        )
    }

    private suspend fun transcodeForTelegram(input: Path, output: Path) {
        val result = processRunner.run(
            FfmpegCommand(
                args = listOf(
                    "-y",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", input.toString(),
                    "-map", "0:v:0",
                    "-map", "0:a:0?",
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
                timeout = FFMPEG_TIMEOUT_MINUTES.minutes,
            )
        )

        if (result.timedOut || result.exitCode != 0) {
            throw VideoPrepareException("ffmpeg failed: ${result.diagnosticOutput.takeLast(500)}")
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
        private const val FFMPEG_TIMEOUT_MINUTES = 20
    }
}

private data class FfmpegCommand(
    override val args: List<String>,
    override val timeout: Duration,
    override val workingDir: Path? = null,
    override val executable: String = "ffmpeg",
) : Command

class VideoTooLargeException(val sizeBytes: Long) :
    RuntimeException(
        "Video is too large for Telegram upload: sizeMb=${
            String.format(Locale.US, "%.2f", sizeBytes / (1024.0 * 1024.0))
        }"
    )

class VideoPrepareException(message: String) : RuntimeException(message)
