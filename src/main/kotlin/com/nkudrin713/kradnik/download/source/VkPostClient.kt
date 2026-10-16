package com.nkudrin713.kradnik.download.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import java.time.Duration

/** Reads the exact public wall item, excluding replies and recommended posts. No credentials are used. */
@Component
class VkPostClient {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = jacksonObjectMapper()

    suspend fun fetch(id: String): JsonNode = try {
        withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                require(id.matches(Regex("-?[1-9]\\d*_[1-9]\\d*")))
                runInterruptible(Dispatchers.IO) {
                    val request = HttpRequest.newBuilder(URI("https://vk.com/wkview.php"))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://vk.com/wkview.php")
                        .POST(HttpRequest.BodyPublishers.ofString("act=show&w=wall$id&al=1"))
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    val bytes = response.body().use { it.readNBytes(MAX_METADATA_BYTES + 1) }
                    if (response.statusCode() !in 200..299) {
                        val reason = if (response.statusCode() in setOf(403, 429)) DownloadFailureReason.SOURCE_RATE_LIMITED else DownloadFailureReason.SOURCE_REQUEST_FAILED
                        throw DownloadFailure(reason, "VK post request failed: status=${response.statusCode()}")
                    }
                    if (bytes.size > MAX_METADATA_BYTES) throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "VK post metadata exceeds size limit")
                    val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                        .find(response.headers().firstValue("Content-Type").orElse(""))?.groupValues?.get(1)?.trim('"') ?: "UTF-8"
                    parse(String(bytes, Charset.forName(charset)), id)
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

    internal fun parse(response: String, id: String): JsonNode {
        val payload = try {
            mapper.readTree(response).path("payload")
        } catch (error: Exception) {
            throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Invalid VK post response", error)
        }
        when (payload.path(0).asInt(-1)) {
            0 -> Unit
            3 -> throw DownloadFailure(DownloadFailureReason.AUTHENTICATION_REQUIRED, "VK post requires authentication")
            8 -> throw DownloadFailure(DownloadFailureReason.SOURCE_UNAVAILABLE, "VK post is unavailable")
            else -> throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Unexpected VK post response")
        }
        val html = payload.path(1).path(1).asText("")
        for (match in DATA_EXEC.findAll(html)) {
            val data = try {
                mapper.readTree(HtmlUtils.htmlUnescape(match.groupValues[1]))
            } catch (_: Exception) {
                continue
            }
            val item = data.path("PostContentContainer/init").path("item")
            if ("${item.path("owner_id").asText()}_${item.path("id").asText()}" == id) {
                if (data.path("PostContentContainer/init").path("attachmentsExpanded").isBoolean && !data.path("PostContentContainer/init").path("attachmentsExpanded").asBoolean()) {
                    throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "VK post attachments are incomplete")
                }
                return item
            }
        }
        throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Exact VK wall item is missing")
    }

    private companion object {
        const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        val DATA_EXEC = Regex("data-exec=\"([^\"]+)\"")
    }
}
