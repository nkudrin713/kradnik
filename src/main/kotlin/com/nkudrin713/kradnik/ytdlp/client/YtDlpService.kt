package com.nkudrin713.kradnik.ytdlp.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.YOUTUBE_PRESET_PREFIX
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.toKotlinDuration

private const val DUMP_SINGLE_JSON = "--dump-single-json"
private const val NO_PLAYLIST = "--no-playlist"
private const val NO_WARNINGS = "--no-warnings"
private const val NO_RESTRICT_FILENAMES = "--no-restrict-filenames"
private const val FORMAT = "-f"
private const val OUTPUT = "-o"
private const val MAX_FILESIZE = "--max-filesize"
private const val PRINT = "--print"
private const val FILEPATH_MARKER = "KRADNIK_FILEPATH:"
private const val FINAL_FILEPATH = "after_move:${FILEPATH_MARKER}%(filepath)j"
private const val EXTRACTOR_ARGS = "--extractor-args"
private const val YOUTUBE_PLAYER_CLIENT = "youtube:player_client=mweb"

private const val TITLE_EXT = "%(title)s.%(ext)s"

@Service
class YtDlpService(
    private val processRunner: ProcessRunner,
    private val uploadLimits: TelegramUploadLimits = TelegramUploadLimits(
        TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES
    ),
    @Value("\${download.youtube.po-token-provider-url:}")
    private val youtubePoTokenProviderUrl: String = "",
    @Value("\${download.yt-dlp.metadata-timeout:30s}")
    private val metadataTimeout: Duration = Duration.ofSeconds(30),
    @Value("\${download.yt-dlp.download-timeout:30m}")
    private val downloadTimeout: Duration = Duration.ofMinutes(30),
) {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    init {
        require(metadataTimeout.isPositive()) { "download.yt-dlp.metadata-timeout must be positive" }
        require(downloadTimeout.isPositive()) { "download.yt-dlp.download-timeout must be positive" }
    }

    suspend fun extractMetadata(request: DownloadRequest): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = buildList {
                    add(DUMP_SINGLE_JSON)
                    add(NO_PLAYLIST)
                    add(NO_WARNINGS)
                    add(FORMAT)
                    add(request.formatSelector)
                    addAll(youtubePoTokenArgs(request))
                    add(request.originalUrl)
                },
                workingDir = null,
                timeout = metadataTimeout.toKotlinDuration(),
            )
        )

        handleBaseErrors(result)
        if (result.stdoutTruncated) {
            throw YtDlpException("yt-dlp metadata extraction output exceeded capture limit")
        }
        if (result.stdout.isBlank()) {
            throw YtDlpException("yt-dlp metadata extraction returned empty output")
        }

        return objectMapper.readValue(result.stdout)
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
            if (uploadLimits.localMode) {
                add(MAX_FILESIZE)
                add(uploadLimits.maxUploadBytes.toString())
            }
            add(PRINT)
            add(FINAL_FILEPATH)
            addAll(youtubePoTokenArgs(request))
            addAll(request.extraArgs)
            add(request.originalUrl)
        }

        val result = processRunner.run(
            YtDlpCommand(
                args = args,
                workingDir = outputDir,
                timeout = downloadTimeout.toKotlinDuration(),
                maxWorkingDirectoryBytes = if (uploadLimits.localMode) {
                    uploadLimits.maxUploadBytes * WORKING_DIRECTORY_LIMIT_MULTIPLIER
                } else {
                    null
                },
            )
        )

        if (result.workingDirectoryLimitExceeded) {
            throw YtDlpFileSizeLimitException(uploadLimits.maxUploadBytes)
        }
        if (uploadLimits.localMode && result.diagnosticOutput.lowercase().contains("max-filesize")) {
            throw YtDlpFileSizeLimitException(uploadLimits.maxUploadBytes)
        }
        handleBaseErrors(result)
        val file = getDownloadedFile(result.stdout)

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

    private fun youtubePoTokenArgs(request: DownloadRequest): List<String> {
        val providerUrl = youtubePoTokenProviderUrl.trim().trimEnd('/')
        if (!request.presetName.startsWith(YOUTUBE_PRESET_PREFIX) || providerUrl.isEmpty()) {
            return emptyList()
        }

        return listOf(
            EXTRACTOR_ARGS,
            YOUTUBE_PLAYER_CLIENT,
            EXTRACTOR_ARGS,
            "youtubepot-bgutilhttp:base_url=$providerUrl",
        )
    }

    private fun handleBaseErrors(result: ProcessExecutionResult) {
        if (result.timedOut) {
            throw YtDlpException("yt-dlp command timed out")
        }

        if (result.exitCode != 0) {
            val diagnosticOutput = result.diagnosticOutput
            if (diagnosticOutput.isAuthenticationRequiredError()) {
                throw YtDlpAuthenticationRequiredException(
                    "yt-dlp authentication required: ${diagnosticOutput.takeLast(500)}"
                )
            }

            throw YtDlpException(
                "yt-dlp command failed: ${diagnosticOutput.takeLast(500)}"
            )
        }
    }

    private fun String.isAuthenticationRequiredError(): Boolean {
        val normalized = lowercase()
        return normalized.contains("login required") ||
                normalized.contains("--cookies") ||
                normalized.contains("--cookies-from-browser")
    }

    private companion object {
        private const val WORKING_DIRECTORY_LIMIT_MULTIPLIER = 2L
    }
}

open class YtDlpException(message: String) : RuntimeException(message)

class YtDlpAuthenticationRequiredException(message: String) : YtDlpException(message)

class YtDlpFileSizeLimitException(val limitBytes: Long) :
    YtDlpException("yt-dlp working directory exceeded safe size limit")
