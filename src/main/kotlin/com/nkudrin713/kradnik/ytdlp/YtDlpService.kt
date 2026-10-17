package com.nkudrin713.kradnik.ytdlp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto
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
private const val IGNORE_NO_FORMATS_ERROR = "--ignore-no-formats-error"
private const val YT_DLP = "yt-dlp"
private const val NO_PLAYLIST = "--no-playlist"
private const val FLAT_PLAYLIST = "--flat-playlist"
private const val NO_WARNINGS = "--no-warnings"
private const val NO_RESTRICT_FILENAMES = "--no-restrict-filenames"
private const val FORMAT = "-f"
private const val OUTPUT = "-o"
private const val MAX_FILESIZE = "--max-filesize"
private const val PRINT = "--print"
private const val OUTPUT_NA_PLACEHOLDER = "--output-na-placeholder"
private const val FILEPATH_MARKER = "KRADNIK_FILEPATH:"
private const val FINAL_FILEPATH = "after_move:${FILEPATH_MARKER}%(filepath)j"

private const val TITLE_EXT = "%(title)s.%(ext)s"
private const val MAX_METADATA_STDOUT_CHARS = 32 * 1024 * 1024
private val SINGLE_MEDIA_METADATA_TEMPLATE = listOf(
    "\"title\":%(title)j",
    "\"thumbnail\":%(thumbnail)j",
    "\"duration\":%(duration)j",
    "\"width\":%(width)j",
    "\"height\":%(height)j",
    "\"filesize\":%(filesize)j",
    "\"filesize_approx\":%(filesize_approx)j",
    "\"track\":%(track)j",
    "\"artist\":%(artist)j",
    "\"uploader\":%(uploader)j",
    "\"channel\":%(channel)j",
    "\"requested_formats\":%(requested_formats.:.{format_id,ext,height,fps,filesize,filesize_approx,vcodec,acodec,tbr,vbr,abr})j",
    "\"formats\":%(formats.:.{format_id,ext,height,fps,filesize,filesize_approx,vcodec,acodec,tbr,vbr,abr})j",
).joinToString(separator = ",", prefix = "{", postfix = "}")

private data class YtDlpCommand(
    override val args: List<String>,
    override val workingDir: Path?,
    override val timeout: kotlin.time.Duration,
    override val maxWorkingDirectoryBytes: Long? = null,
    override val maxCapturedStdoutChars: Int? = null,
    override val executable: String = YT_DLP,
) : Command

/**
 * Implements the yt-dlp boundary used by [DownloadEngine][com.nkudrin713.kradnik.download.DownloadEngine].
 * It builds shell-free [Command] arguments for [ProcessRunner], parses metadata JSON, applies configured time and
 * workspace limits, classifies authentication failures, and accepts only the final regular file reported by yt-dlp.
 */
@Service
class YtDlpService(
    private val processRunner: ProcessRunner,
    private val uploadLimits: TelegramUploadLimits = TelegramUploadLimits(
        TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES,
    ),
    @Value($$"${download.yt-dlp.metadata-timeout:30s}")
    private val metadataTimeout: Duration = Duration.ofSeconds(30),
    @Value($$"${download.yt-dlp.download-timeout:30m}")
    private val downloadTimeout: Duration = Duration.ofMinutes(30),
    @Value($$"${download.yt-dlp.cloud-max-workspace-bytes:536870912}")
    private val cloudMaxWorkspaceBytes: Long = 536_870_912,
) {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    init {
        require(metadataTimeout.isPositive) { "download.yt-dlp.metadata-timeout must be positive" }
        require(downloadTimeout.isPositive) { "download.yt-dlp.download-timeout must be positive" }
        require(cloudMaxWorkspaceBytes > 0) { "download.yt-dlp.cloud-max-workspace-bytes must be positive" }
    }

    /**
     * Extracts metadata for [SourceRequest.formatSelector], including selected-format data used by preflight checks.
     * Disables playlist expansion and applies the metadata timeout without downloading media.
     * Process failures and empty or truncated output are rejected before JSON parsing.
     */
    suspend fun extractMetadata(spec: SourceRequest, options: List<String> = emptyList()): YtDlpMetadataDto {
        return extractMetadata(spec, spec.formatSelector, options)
    }

    /**
     * Leaves the format selector unset so [DownloadChoicePlanner][com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner]
     * receives the available format catalog. Uses the same single-item extraction and failure checks as [extractMetadata].
     */
    suspend fun extractCatalogMetadata(spec: SourceRequest, options: List<String> = emptyList()): YtDlpMetadataDto {
        return extractMetadata(spec, formatSelector = null, options)
    }

    /**
     * Extracts a flat playlist listing without downloading media or resolving each entry's formats.
     * Applies the metadata timeout and adapter options; item-count validation belongs to the caller.
     * Rejects failed, empty, or truncated process output before parsing the playlist metadata.
     */
    suspend fun extractPlaylistMetadata(url: String, options: List<String> = emptyList()): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = buildList {
                    add(DUMP_SINGLE_JSON)
                    add(FLAT_PLAYLIST)
                    add(NO_WARNINGS)
                    addAll(options)
                    add(url)
                },
                workingDir = null,
                timeout = metadataTimeout.toKotlinDuration(),
                maxCapturedStdoutChars = MAX_METADATA_STDOUT_CHARS,
            ),
        )
        return parseMetadataResult(result)
    }

    /**
     * Extracts posts and mixed carousels without treating absent video formats as an error.
     * Applies the metadata timeout and normal process/output checks; this does not download the images.
     */
    suspend fun extractImageMetadata(spec: SourceRequest): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = listOf(
                    DUMP_SINGLE_JSON,
                    IGNORE_NO_FORMATS_ERROR,
                    NO_WARNINGS,
                    spec.originalUrl,
                ),
                workingDir = null,
                timeout = metadataTimeout.toKotlinDuration(),
                maxCapturedStdoutChars = MAX_METADATA_STDOUT_CHARS,
            ),
        )
        return parseMetadataResult(result)
    }

    suspend fun extractPostMetadata(spec: SourceRequest, catalog: Boolean, options: List<String> = emptyList()): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = buildList {
                    addAll(listOf(DUMP_SINGLE_JSON, NO_PLAYLIST, NO_WARNINGS))
                    addAll(options)
                    if (!catalog) addAll(listOf(FORMAT, spec.formatSelector))
                    add(spec.originalUrl)
                },
                workingDir = null,
                timeout = metadataTimeout.toKotlinDuration(),
                maxCapturedStdoutChars = MAX_METADATA_STDOUT_CHARS,
            ),
        )
        return parseMetadataResult(result)
    }

    private suspend fun extractMetadata(
        spec: SourceRequest,
        formatSelector: String?,
        options: List<String>,
    ): YtDlpMetadataDto {
        val result = processRunner.run(
            YtDlpCommand(
                args = buildList {
                    add(PRINT)
                    add(SINGLE_MEDIA_METADATA_TEMPLATE)
                    add(OUTPUT_NA_PLACEHOLDER)
                    add("null")
                    add(NO_PLAYLIST)
                    add(NO_WARNINGS)
                    if (formatSelector != null) {
                        add(FORMAT)
                        add(formatSelector)
                    }
                    addAll(options)
                    add(spec.originalUrl)
                },
                workingDir = null,
                timeout = metadataTimeout.toKotlinDuration(),
                maxCapturedStdoutChars = MAX_METADATA_STDOUT_CHARS,
            ),
        )

        return parseMetadataResult(result)
    }

    private fun parseMetadataResult(result: ProcessExecutionResult): YtDlpMetadataDto {
        handleBaseErrors(result)
        if (result.stdoutTruncated) {
            throw YtDlpException("yt-dlp metadata extraction output exceeded capture limit")
        }
        if (result.stdout.isBlank()) {
            throw YtDlpException("yt-dlp metadata extraction returned empty output")
        }
        return objectMapper.readValue(result.stdout)
    }

    /**
     * Downloads [spec] and returns only the regular final path emitted by yt-dlp after merging or post-processing.
     * Uses [outputDir] as the process workspace and disables playlist expansion in the base arguments.
     * Workspace growth is bounded in both API modes; local Bot API mode also supplies yt-dlp's file-size limit.
     * Timeout, process failure, workspace growth, missing marker, and missing file are reported as typed failures.
     * The caller owns cleanup of downloaded and partial files in [outputDir].
     */
    suspend fun download(
        spec: SourceRequest,
        outputDir: Path,
        options: List<String> = emptyList(),
    ): DownloadedFile {
        val args = buildList {
            add(NO_PLAYLIST)
            add(NO_WARNINGS)
            add(NO_RESTRICT_FILENAMES)
            add(FORMAT)
            add(spec.formatSelector)
            add(OUTPUT)
            add(TITLE_EXT)
            if (uploadLimits.localMode) {
                add(MAX_FILESIZE)
                add(uploadLimits.maxUploadBytes.toString())
            }
            add(PRINT)
            add(FINAL_FILEPATH)
            addAll(options)
            addAll(spec.audioQuality?.let { YtDlpPresets.withAudioQuality(spec.extraArgs, it) } ?: spec.extraArgs)
            add(spec.originalUrl)
        }

        val result = processRunner.run(
            YtDlpCommand(
                args = args,
                workingDir = outputDir,
                timeout = downloadTimeout.toKotlinDuration(),
                maxWorkingDirectoryBytes = maxWorkspaceBytes(),
            ),
        )

        if (result.workingDirectoryLimitExceeded) {
            throw YtDlpFileSizeLimitException()
        }
        if (uploadLimits.localMode && result.diagnosticOutput.lowercase().contains("max-filesize")) {
            throw YtDlpFileSizeLimitException()
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
        val paths = output
            .lineSequence()
            .filter { it.startsWith(FILEPATH_MARKER) }
            .toList()
        if (paths.size > 1) throw YtDlpException("yt-dlp returned multiple files for a single media item")
        val path = paths.singleOrNull()
            ?.removePrefix(FILEPATH_MARKER)
            ?.let { objectMapper.readValue<String>(it) }
            ?: throw YtDlpException("yt-dlp download did not print final filepath")

        val file = Path.of(path)

        if (!file.isRegularFile()) {
            throw YtDlpException("yt-dlp download file not found: $path")
        }

        return file
    }

    private fun maxWorkspaceBytes(): Long {
        return if (uploadLimits.localMode) {
            uploadLimits.maxUploadBytes * WORKING_DIRECTORY_LIMIT_MULTIPLIER
        } else {
            cloudMaxWorkspaceBytes
        }
    }

    private fun handleBaseErrors(result: ProcessExecutionResult) {
        if (result.timedOut) {
            throw YtDlpException("yt-dlp command timed out", DownloadFailureReason.SOURCE_TIMEOUT)
        }

        if (result.exitCode != 0) {
            val diagnosticOutput = result.diagnosticOutput
            val reason = diagnosticOutput.failureReason()
            if (reason == DownloadFailureReason.AUTHENTICATION_REQUIRED) {
                throw YtDlpAuthenticationRequiredException(
                    "yt-dlp authentication required: ${diagnosticOutput.takeLast(500)}",
                )
            }

            throw YtDlpException(
                "yt-dlp command failed: ${diagnosticOutput.takeLast(500)}",
                reason,
            )
        }
    }

    private fun String.failureReason(): DownloadFailureReason {
        val text = lowercase()
        return when {
            "requested format is not available" in text || "no downloadable video" in text || "contains no videos" in text -> DownloadFailureReason.FORMAT_UNAVAILABLE

            "this content isn't available to everyone" in text || "can't be seen by certain audiences" in text ||
                "private video" in text || "video is private" in text || "video has been removed" in text ||
                "video has been deleted" in text || "video does not exist" in text ||
                "not available in your country" in text || "not made this video available in your country" in text -> DownloadFailureReason.SOURCE_UNAVAILABLE

            "rate-limit reached or login required" in text -> DownloadFailureReason.SOURCE_REQUEST_FAILED

            "sign in to confirm" in text && "not a bot" in text -> DownloadFailureReason.SOURCE_REQUEST_FAILED

            "http error 429" in text || "too many requests" in text || "rate-limit reached" in text ||
                "try again later" in text -> DownloadFailureReason.SOURCE_RATE_LIMITED

            text.isAuthenticationRequiredError() -> DownloadFailureReason.AUTHENTICATION_REQUIRED

            "timed out" in text || "timeout" in text -> DownloadFailureReason.SOURCE_TIMEOUT

            "connection reset" in text || "connection refused" in text || "network is unreachable" in text ||
                "temporary failure in name resolution" in text || "http error 502" in text ||
                "http error 503" in text || "http error 504" in text -> DownloadFailureReason.SOURCE_REQUEST_FAILED

            else -> DownloadFailureReason.SOURCE_FAILED
        }
    }

    private fun String.isAuthenticationRequiredError(): Boolean {
        val normalized = lowercase()
        return normalized.contains("login required") ||
            normalized.contains("sign in to confirm") ||
            normalized.contains("sign in to view") ||
            normalized.contains("--cookies") ||
            normalized.contains("--cookies-from-browser")
    }

    private companion object {
        private const val WORKING_DIRECTORY_LIMIT_MULTIPLIER = 2L
    }
}

open class YtDlpException(message: String, reason: DownloadFailureReason = DownloadFailureReason.SOURCE_FAILED) : DownloadFailure(reason, message)

class YtDlpAuthenticationRequiredException(message: String) : YtDlpException(message, DownloadFailureReason.AUTHENTICATION_REQUIRED)

class YtDlpFileSizeLimitException : YtDlpException("yt-dlp working directory exceeded safe size limit", DownloadFailureReason.TOO_LARGE)
