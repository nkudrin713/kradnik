package com.nkudrin713.kradnik.ytdlp.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val DUMP_SINGLE_JSON = "--dump-single-json"
private const val NO_PLAYLIST = "--no-playlist"
private const val NO_WARNINGS = "--no-warnings"
private const val NO_RESTRICT_FILENAMES = "--no-restrict-filenames"
private const val FORMAT = "-f"
private const val OUTPUT = "-o"
private const val PRINT = "--print"
private const val FILEPATH_MARKER = "KRADNIK_FILEPATH:"
private const val FINAL_FILEPATH = "after_move:${FILEPATH_MARKER}%(filepath)j"

private const val TITLE_EXT = "%(title)s.%(ext)s"

@Service
class YtDlpService(
    private val processRunner: ProcessRunner,
) {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    suspend fun extractMetadata(url: String): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = listOf(
                    DUMP_SINGLE_JSON,
                    NO_PLAYLIST,
                    NO_WARNINGS,
                    url,
                ),
                workingDir = null,
                timeout = 30.seconds,
            )
        )

        handleBaseErrors("metadata extraction", result)

        if (result.output.isBlank()) {
            throw YtDlpException("yt-dlp metadata extraction returned empty output")
        }

        return objectMapper.readValue(result.output)
    }

    suspend fun inspect(request: DownloadRequest): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = listOf(
                    DUMP_SINGLE_JSON,
                    NO_PLAYLIST,
                    NO_WARNINGS,
                    FORMAT,
                    request.formatSelector,
                    request.originalUrl,
                ),
                workingDir = null,
                timeout = 30.seconds,
            )
        )

        handleBaseErrors("preflight inspection", result)

        if (result.output.isBlank()) {
            throw YtDlpException("yt-dlp preflight inspection returned empty output")
        }

        return objectMapper.readValue(result.output)
    }

    suspend fun download(
        request: DownloadRequest,
        outputDir: Path,
    ): DownloadedFile {
        val args = buildList {
            add(NO_PLAYLIST)
            add(NO_WARNINGS)
            add(NO_RESTRICT_FILENAMES)
            add(FORMAT)
            add(request.formatSelector)
            add(OUTPUT)
            add(TITLE_EXT)
            add(PRINT)
            add(FINAL_FILEPATH)
            addAll(request.extraArgs)
            add(request.originalUrl)
        }

        val result = processRunner.run(
            YtDlpCommand(
                args = args,
                workingDir = outputDir,
                timeout = 10.minutes,
            )
        )

        handleBaseErrors("download", result)

        val file = getDownloadedFile(result.output)

        return DownloadedFile(
            file = file,
            sizeBytes = withContext(Dispatchers.IO) {
                file.fileSize()
            },
        )
    }

    private fun getDownloadedFile(output: String): Path {
        val path = output
            .lineSequence()
            .lastOrNull { it.startsWith(FILEPATH_MARKER) }
            ?.removePrefix(FILEPATH_MARKER)
            ?.let { objectMapper.readValue<String>(it) }
            ?: throw YtDlpException("yt-dlp download did not print final filepath")

        val file = Path.of(path)

        if (!file.isRegularFile()) {
            throw YtDlpException("yt-dlp download file not found: $path")
        }

        return file
    }

    private fun handleBaseErrors(
        operation: String,
        result: ProcessExecutionResult,
    ) {
        if (result.timedOut) {
            throw YtDlpException("yt-dlp $operation timed out")
        }

        if (result.exitCode != 0) {
            throw YtDlpException(
                "yt-dlp $operation failed: ${result.output.takeLast(500)}"
            )
        }
    }
}

class YtDlpException(message: String) : RuntimeException(message)
