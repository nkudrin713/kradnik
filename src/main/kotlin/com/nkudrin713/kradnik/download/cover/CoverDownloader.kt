package com.nkudrin713.kradnik.download.cover

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Downloads supported image types with both header and streaming size enforcement. */
@Component
class CoverDownloader(
    uploadLimits: TelegramUploadLimits,
) {
    private val maxCoverBytes = minOf(uploadLimits.maxUploadBytes, MAX_COVER_BYTES)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun download(url: String, outputDir: Path): DownloadedFile = withContext(Dispatchers.IO) {
        val uri = runCatching { URI.create(url) }
            .getOrElse { throw CoverDownloadException("Cover URL is invalid") }
        if (uri.scheme != "https" && uri.scheme != "http") {
            throw CoverDownloadException("Cover URL scheme is not supported")
        }
        val response = httpClient.send(
            HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "image/*")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() !in SUCCESS_STATUS_CODES) {
            response.body().close()
            throw CoverDownloadException("Cover request failed: status=${response.statusCode()}")
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
            .substringBefore(';')
            .trim()
            .lowercase()
        val extension = EXTENSIONS[contentType]
            ?: run {
                response.body().close()
                throw CoverDownloadException("Cover response is not a supported image")
            }
        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        if (contentLength > maxCoverBytes) {
            response.body().close()
            throw CoverTooLargeException(contentLength)
        }

        val outputFile = outputDir.resolve("cover.$extension")
        try {
            response.body().use { input ->
                Files.newOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloadedBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        if (downloadedBytes > maxCoverBytes) {
                            throw CoverTooLargeException(downloadedBytes)
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Exception) {
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
        private val REQUEST_TIMEOUT = Duration.ofMinutes(2)
        private val SUCCESS_STATUS_CODES = 200..299
        private const val MAX_COVER_BYTES = 20_000_000L
        private const val BUFFER_BYTES = 64 * 1024
        private val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
        )
    }
}

open class CoverDownloadException(message: String) : RuntimeException(message)

class CoverTooLargeException(val sizeBytes: Long) :
    CoverDownloadException("Cover exceeds the upload limit")
