package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Defines the HTTP operations used by [InstagramEmbedDownloader] for public embed metadata and direct media.
 * Implementations return typed failures and a [DownloadedFile] without exposing transport details upstream.
 */
interface InstagramHttpClient {
    /**
     * Fetches the embed page as UTF-8 text for metadata parsing.
     * Non-success HTTP responses raise [InstagramHttpException] with the embed request stage.
     */
    suspend fun getText(uri: URI): String

    /**
     * Probes the advertised video size without downloading the body.
     * Returns null for a non-success status or a missing or negative length; transport failures propagate.
     * The returned size is a preflight hint, not a guarantee about a subsequent download.
     */
    suspend fun contentLength(uri: URI): Long?

    /**
     * Downloads a response with a video content type into [outputFile] and reports the actual file size.
     * The caller supplies an existing parent directory and owns workspace cleanup after use or failure.
     */
    suspend fun download(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile

    /**
     * Downloads a response with an image content type into [outputFile], enforcing the photo size limit.
     * The caller supplies an existing parent directory and owns workspace cleanup after use or failure.
     */
    suspend fun downloadImage(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile
}

/**
 * Implements [InstagramHttpClient] with JDK HTTP calls on the IO dispatcher.
 * Validates response status and the expected media content type. Size-limit failures remove partial files;
 * other failures leave cleanup to the workspace owner. Video streams enforce [TelegramUploadLimits] in
 * local Bot API mode, while image streams always enforce the photo size limit.
 */
@Component
class JdkInstagramHttpClient(
    private val uploadLimits: TelegramUploadLimits = TelegramUploadLimits(
        TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES,
    ),
    @Value($$"${download.instagram.metadata-timeout:30s}")
    private val metadataTimeout: Duration = Duration.ofSeconds(30),
    @Value($$"${download.instagram.download-timeout:10m}")
    private val downloadTimeout: Duration = Duration.ofMinutes(10),
) : InstagramHttpClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(metadataTimeout.isPositive) { "download.instagram.metadata-timeout must be positive" }
        require(downloadTimeout.isPositive) { "download.instagram.download-timeout must be positive" }
    }

    override suspend fun getText(uri: URI): String = runInterruptible(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(metadataTimeout)
            .header("Accept", "text/html")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        if (response.statusCode() !in SUCCESS_STATUS_CODES) {
            throw InstagramHttpException(
                stage = InstagramRequestStage.EMBED,
                statusCode = response.statusCode(),
            )
        }

        logger.info(
            "Instagram embed response accepted: host={}, status={}, contentType={}, contentLength={}",
            uri.host,
            response.statusCode(),
            response.headers().firstValue("Content-Type").orElse(null),
            response.headers().firstValueAsLong("Content-Length").orElse(-1),
        )
        response.body()
    }

    override suspend fun contentLength(uri: URI): Long? = runInterruptible(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(metadataTimeout)
            .header("Accept", "video/*")
            .header("User-Agent", USER_AGENT)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in SUCCESS_STATUS_CODES) {
            return@runInterruptible null
        }
        response.headers().firstValueAsLong("Content-Length")
            .orElse(-1L)
            .takeIf { it >= 0L }
    }

    override suspend fun download(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile = downloadContent(
        uri = uri,
        outputFile = outputFile,
        expectedContentType = "video/",
        maxBytes = uploadLimits.maxUploadBytes.takeIf { uploadLimits.localMode },
    )

    override suspend fun downloadImage(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile = downloadContent(
        uri = uri,
        outputFile = outputFile,
        expectedContentType = "image/",
        maxBytes = MAX_PHOTO_BYTES,
    )

    /**
     * Checks status, content type, and any declared size before opening the output file, then enforces
     * [maxBytes] while copying the body. A null limit disables both size checks.
     * Only a streaming size-limit failure deletes [outputFile] here; other I/O failures propagate as-is.
     */
    private suspend fun downloadContent(
        uri: URI,
        outputFile: Path,
        expectedContentType: String,
        maxBytes: Long?,
    ): DownloadedFile = runInterruptible(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(downloadTimeout)
            .header("Accept", "$expectedContentType*")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream(),
        )

        if (response.statusCode() !in SUCCESS_STATUS_CODES) {
            response.body().close()
            throw InstagramHttpException(
                stage = InstagramRequestStage.MEDIA,
                statusCode = response.statusCode(),
            )
        }

        val contentType = response.headers()
            .firstValue("Content-Type")
            .orElse("")
        if (!contentType.startsWith(expectedContentType)) {
            response.body().close()
            throw InstagramEmbedException(
                "Instagram media response has unexpected content type: expected=$expectedContentType, actual=$contentType",
            )
        }

        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        if (maxBytes != null && contentLength > maxBytes) {
            response.body().close()
            throw InstagramMediaTooLargeException()
        }

        logger.info(
            "Instagram media response accepted: host={}, status={}, contentType={}, contentLength={}",
            uri.host,
            response.statusCode(),
            contentType,
            contentLength,
        )
        try {
            response.body().use { input ->
                Files.newOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloadedBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        downloadedBytes += count
                        if (maxBytes != null && downloadedBytes > maxBytes) {
                            throw InstagramMediaTooLargeException()
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: InstagramMediaTooLargeException) {
            Files.deleteIfExists(outputFile)
            throw error
        }

        DownloadedFile(
            file = outputFile,
            sizeBytes = Files.size(outputFile),
        )
    }

    private companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val SUCCESS_STATUS_CODES = 200..299
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MAX_PHOTO_BYTES = 10_000_000L
        private const val USER_AGENT = "Mozilla/5.0"
    }
}

enum class InstagramRequestStage {
    EMBED,
    MEDIA,
}

class InstagramHttpException(
    stage: InstagramRequestStage,
    val statusCode: Int,
) : InstagramEmbedException(
    "Instagram ${stage.name.lowercase()} request failed: status=$statusCode",
    reason = if (statusCode == 403 || statusCode == 429) DownloadFailureReason.SOURCE_RATE_LIMITED else DownloadFailureReason.SOURCE_REQUEST_FAILED,
)

class InstagramMediaTooLargeException : InstagramEmbedException("Instagram media exceeds Telegram upload limit", reason = DownloadFailureReason.TOO_LARGE)
