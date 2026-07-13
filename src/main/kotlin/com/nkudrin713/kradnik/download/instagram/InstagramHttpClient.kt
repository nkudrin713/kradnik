package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            throw InstagramEmbedException("Instagram embed request failed: status=${response.statusCode()}")
        }

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
            throw InstagramEmbedException("Instagram media download failed: status=${response.statusCode()}")
        }

        val contentType = response.headers()
            .firstValue("Content-Type")
            .orElse("")
        if (!contentType.startsWith("video/")) {
            response.body().close()
            throw InstagramEmbedException("Instagram media response is not a video: contentType=$contentType")
        }

        response.body().use { input ->
            Files.copy(input, outputFile, StandardCopyOption.REPLACE_EXISTING)
        }

        DownloadedFile(
            file = outputFile,
            sizeBytes = Files.size(outputFile),
        )
    }

    private companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val METADATA_TIMEOUT = Duration.ofSeconds(30)
        private val DOWNLOAD_TIMEOUT = Duration.ofMinutes(10)
        private val SUCCESS_STATUS_CODES = 200..299
        private const val USER_AGENT = "Mozilla/5.0"
    }
}
