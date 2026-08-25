package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The HTTP boundary for public Instagram embed metadata and direct media. */
interface InstagramHttpClient {
    suspend fun getText(uri: URI): String

    suspend fun contentLength(uri: URI): Long?

    suspend fun download(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile
}

/**
 * Runs JDK HTTP calls on the IO dispatcher and validates media while streaming it.
 * Local Bot API mode stops the stream as soon as its upload limit is exceeded.
 */
@Component
class JdkInstagramHttpClient(
    private val uploadLimits: TelegramUploadLimits = TelegramUploadLimits(
        TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES
    ),
    @Value("\${download.instagram.metadata-timeout:30s}")
    private val metadataTimeout: Duration = Duration.ofSeconds(30),
    @Value("\${download.instagram.download-timeout:10m}")
    private val downloadTimeout: Duration = Duration.ofMinutes(10),
) : InstagramHttpClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(metadataTimeout.isPositive()) { "download.instagram.metadata-timeout must be positive" }
        require(downloadTimeout.isPositive()) { "download.instagram.download-timeout must be positive" }
    }

    override suspend fun getText(uri: URI): String = withContext(Dispatchers.IO) {
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
                retryAfter = parseRetryAfter(response),
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

    override suspend fun contentLength(uri: URI): Long? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(metadataTimeout)
            .header("Accept", "video/*")
            .header("User-Agent", USER_AGENT)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in SUCCESS_STATUS_CODES) {
            return@withContext null
        }
        response.headers().firstValueAsLong("Content-Length")
            .orElse(-1L)
            .takeIf { it >= 0L }
    }

    override suspend fun download(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(downloadTimeout)
            .header("Accept", "video/*")
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
                retryAfter = parseRetryAfter(response),
            )
        }

        val contentType = response.headers()
            .firstValue("Content-Type")
            .orElse("")
        if (!contentType.startsWith("video/")) {
            response.body().close()
            throw InstagramEmbedException("Instagram media response is not a video: contentType=$contentType")
        }

        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        if (uploadLimits.localMode && contentLength > uploadLimits.maxUploadBytes) {
            response.body().close()
            throw InstagramMediaTooLargeException(contentLength)
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
                        if (uploadLimits.localMode && downloadedBytes > uploadLimits.maxUploadBytes) {
                            throw InstagramMediaTooLargeException(downloadedBytes)
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

    private fun parseRetryAfter(response: HttpResponse<*>): Duration? {
        val value = response.headers().firstValue("Retry-After").orElse(null) ?: return null
        value.toLongOrNull()?.let { seconds ->
            return Duration.ofSeconds(seconds.coerceAtLeast(0))
        }

        return runCatching {
            val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(Instant.now(), retryAt).coerceAtLeast(Duration.ZERO)
        }.getOrNull()
    }

    private companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val SUCCESS_STATUS_CODES = 200..299
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val USER_AGENT = "Mozilla/5.0"
    }
}

enum class InstagramRequestStage {
    EMBED,
    MEDIA,
}

class InstagramHttpException(
    val stage: InstagramRequestStage,
    val statusCode: Int,
    val retryAfter: Duration?,
) : InstagramEmbedException("Instagram ${stage.name.lowercase()} request failed: status=$statusCode")

class InstagramMediaTooLargeException(val sizeBytes: Long) :
    InstagramEmbedException("Instagram media exceeds Telegram upload limit")
