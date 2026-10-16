package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Bounded media transfer shared by source adapters; callers own the workspace. */
@Component
class HttpMediaDownloader {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    suspend fun download(uri: URI, file: Path, contentType: String, maxBytes: Long?, timeout: Duration): DownloadedFile = try {
        withContext(Dispatchers.IO) {
            withTimeout(timeout.toMillis()) {
                runInterruptible {
                    val request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "Mozilla/5.0").header("Accept", "$contentType*").GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    response.body().use { input ->
                        if (response.statusCode() !in 200..299) throw MediaHttpException(response.statusCode())
                        if (!response.headers().firstValue("Content-Type").orElse("").startsWith(contentType, ignoreCase = true)) {
                            throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Unexpected media content type")
                        }
                        if (maxBytes != null && response.headers().firstValueAsLong("Content-Length").orElse(-1) > maxBytes) throw DownloadRejectedException("Media exceeds size limit")
                        try {
                            Files.newOutputStream(file).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (maxBytes != null && total > maxBytes) throw DownloadRejectedException("Media exceeds size limit")
                                    output.write(buffer, 0, count)
                                }
                            }
                        } catch (error: DownloadRejectedException) {
                            Files.deleteIfExists(file)
                            throw error
                        }
                    }
                    DownloadedFile(file, Files.size(file))
                }
            }
        }
    } catch (error: TimeoutCancellationException) {
        throw DownloadFailure(DownloadFailureReason.SOURCE_TIMEOUT, "Source request timed out", error)
    } catch (error: HttpTimeoutException) {
        throw DownloadFailure(DownloadFailureReason.SOURCE_TIMEOUT, "Source request timed out", error)
    } catch (error: IOException) {
        throw DownloadFailure(DownloadFailureReason.SOURCE_REQUEST_FAILED, "Source request failed", error)
    }
}

class MediaHttpException(val statusCode: Int) :
    DownloadFailure(
        if (statusCode in setOf(403, 429)) DownloadFailureReason.SOURCE_RATE_LIMITED else DownloadFailureReason.SOURCE_REQUEST_FAILED,
        "Media request failed: status=$statusCode",
    )
