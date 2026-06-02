package com.nkudrin713.kradnik.ytdlp.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val DUMP_SINGLE_JSON = "--dump-single-json"
private const val NO_PLAYLIST = "--no-playlist"
private const val NO_WARNINGS = "--no-warnings"
private const val FORMAT = "-f"
private const val EXTRACT_AUDIO = "-x"
private const val AUDIO_FORMAT = "--audio-format"
private const val AUDIO_QUALITY = "--audio-quality"
private const val RECODE_VIDEO = "--recode-video"
private const val POSTPROCESSOR_ARGS = "--postprocessor-args"
private const val OUTPUT = "-o"
private const val RESTRICT_FILENAMES = "--restrict-filenames"
private const val TITLE_EXT = "%(title)s.%(ext)s"
private const val PRINT = "--print"
private const val FINAL_FILEPATH = "after_move:filepath"
private const val MP3 = "mp3"
private const val MP4 = "mp4"
private const val BESTAUDIO_BEST = "bestaudio/best"
private const val BEST_VIDEO_AUDIO = "bv*+ba/b"

@Service
class YtDlpService(
    private val processRunner: ProcessRunner,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    suspend fun extractMetadata(url: String, chatId: Long? = null, taskId: Long? = null): YtDlpMetadataDto {
        if (chatId != null && taskId != null) {
            logger.info("CHAT[{}] TASK[{}] metadata start", chatId, taskId)
        }
        val result = processRunner.run(
            YtDlpCommand(
                args = listOf(DUMP_SINGLE_JSON, NO_PLAYLIST, NO_WARNINGS, url),
                workingDir = null,
                timeout = 30.seconds,
            )
        )
        handleBaseErrors("metadata extraction", result)
        if (result.output.isBlank()) {
            throw YtDlpException("yt-dlp metadata extraction returned empty output")
        }

        return objectMapper.readValue<YtDlpMetadataDto>(result.output)
    }

    suspend fun downloadAudio(url: String, outputDir: Path, audioQuality: String): DownloadedFile {
        val args = listOf(
            NO_PLAYLIST,
            NO_WARNINGS,
            FORMAT,
            BESTAUDIO_BEST,
            EXTRACT_AUDIO,
            AUDIO_FORMAT,
            MP3,
            AUDIO_QUALITY,
            audioQuality,
            OUTPUT,
            TITLE_EXT,
            RESTRICT_FILENAMES,
            PRINT,
            FINAL_FILEPATH,
            url
        )
        val result = processRunner.run(
            YtDlpCommand(
                args = args,
                workingDir = outputDir,
                timeout = 10.minutes,
            )
        )

        handleBaseErrors("audio download", result)

        val file = getDownloadedFile(result.output)
        return DownloadedFile(
            file = file,
            ext = file.extension,
            sizeBytes = file.fileSize(),
            args = args,
        )
    }

    suspend fun downloadVideo(url: String, outputDir: Path, crf: Int, audioBitrate: String): DownloadedFile {
        val args = listOf(
            NO_PLAYLIST,
            NO_WARNINGS,
            FORMAT,
            BEST_VIDEO_AUDIO,
            RECODE_VIDEO,
            MP4,
            POSTPROCESSOR_ARGS,
            "VideoConvertor:-c:v libx264 -preset slow -crf $crf -c:a aac -b:a $audioBitrate -movflags +faststart",
            OUTPUT,
            TITLE_EXT,
            RESTRICT_FILENAMES,
            PRINT,
            FINAL_FILEPATH,
            url
        )
        val result = processRunner.run(
            YtDlpCommand(
                args = args,
                workingDir = outputDir,
                timeout = 20.minutes,
            )
        )

        handleBaseErrors("video download", result)

        val file = getDownloadedFile(result.output, "video download")
        return DownloadedFile(
            file = file,
            ext = file.extension,
            sizeBytes = file.fileSize(),
            args = args,
        )
    }

    private fun getDownloadedFile(output: String, operation: String = "audio download"): Path {
        val path = output.lineSequence().lastOrNull(String::isNotBlank)
            ?: throw YtDlpException("yt-dlp $operation did not print final filepath")
        val file = kotlin.io.path.Path(path)

        if (!file.isRegularFile()) {
            throw YtDlpException("yt-dlp $operation file not found: $path")
        }

        return file
    }

    private fun handleBaseErrors(operation: String, result: ProcessExecutionResult) {
        if (result.timedOut) {
            throw YtDlpException("yt-dlp ${operation} timed out")
        }
        if (result.exitCode != 0) {
            throw YtDlpException("yt-dlp ${operation} failed: ${result.output.takeLast(500)}")
        }
    }

}

class YtDlpException(message: String) : RuntimeException(message)
