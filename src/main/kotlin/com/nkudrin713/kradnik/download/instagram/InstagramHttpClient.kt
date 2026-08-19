package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

interface InstagramHttpClient {
    suspend fun getText(uri: URI): String

    suspend fun download(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile
}

@Component
class JdkInstagramHttpClient : InstagramHttpClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun getText(uri: URI): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(METADATA_TIMEOUT)
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

    override suspend fun download(
        uri: URI,
        outputFile: Path,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(DOWNLOAD_TIMEOUT)
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

        logger.info(
            "Instagram media response accepted: host={}, status={}, contentType={}, contentLength={}",
            uri.host,
            response.statusCode(),
            contentType,
            response.headers().firstValueAsLong("Content-Length").orElse(-1),
        )
        response.body().use { input ->
            Files.copy(input, outputFile, StandardCopyOption.REPLACE_EXISTING)
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
        private val METADATA_TIMEOUT = Duration.ofSeconds(30)
        private val DOWNLOAD_TIMEOUT = Duration.ofMinutes(10)
        private val SUCCESS_STATUS_CODES = 200..299
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
