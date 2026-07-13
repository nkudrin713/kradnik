package com.nkudrin713.kradnik.download.instagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Path

@Service
class InstagramEmbedDownloader(
    private val httpClient: InstagramHttpClient,
) {
    private val objectMapper = jacksonObjectMapper()

    fun supports(request: DownloadRequest): Boolean {
        if (request.outputType != OutputType.VIDEO) {
            return false
        }

        return extractShortcode(request.originalUrl) != null
    }

    suspend fun prepare(request: DownloadRequest): InstagramPreparedDownload {
        val shortcode = extractShortcode(request.originalUrl)
            ?: throw InstagramEmbedException("Instagram URL is not supported by embed downloader")
        val embedUri = URI.create("https://www.instagram.com/p/$shortcode/embed/captioned/")
        val html = try {
            httpClient.getText(embedUri)
        } catch (error: InstagramEmbedException) {
            throw error
        } catch (error: Exception) {
            throw InstagramEmbedException("Instagram embed request failed", error)
        }
        val context = extractContext(html)
        val mediaUri = context
            .findFirstText(VIDEO_URL)
            ?.let(::parseMediaUri)
            ?: throw InstagramEmbedException("Instagram embed response does not contain video URL")

        return InstagramPreparedDownload(
            shortcode = shortcode,
            mediaUri = mediaUri,
            metadata = YtDlpMetadataDto(
                id = shortcode,
                title = "Instagram $shortcode",
                extractor = "instagram:embed",
                webpageUrl = request.normalizedUrl,
                thumbnail = context.findFirstText(DISPLAY_URL, THUMBNAIL_URL),
                duration = context.findFirstDecimal(VIDEO_DURATION),
                ext = "mp4",
                width = context.findFirstInt(ORIGINAL_WIDTH, WIDTH),
                height = context.findFirstInt(ORIGINAL_HEIGHT, HEIGHT),
                fps = null,
                filesize = null,
                vcodec = null,
                acodec = null,
                filesizeApprox = null,
                formatId = "instagram_embed_mp4",
                format = "Instagram embed MP4",
                track = null,
                artist = null,
                creator = null,
                uploader = context.findFirstText(USERNAME),
                channel = null,
                requestedFormats = null,
            ),
        )
    }

    suspend fun download(
        preparedDownload: InstagramPreparedDownload,
        outputDir: Path,
    ): DownloadedFile {
        return httpClient.download(
            uri = preparedDownload.mediaUri,
            outputFile = outputDir.resolve("instagram-${preparedDownload.shortcode}.mp4"),
        )
    }

    private fun extractContext(html: String): JsonNode {
        val payload = EMBED_PAYLOAD_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            ?: throw InstagramEmbedException("Instagram embed payload is missing")
        val payloadNode = try {
            objectMapper.readTree(payload)
        } catch (error: Exception) {
            throw InstagramEmbedException("Instagram embed payload is invalid", error)
        }
        val contextJson = payloadNode.path(CONTEXT_JSON).takeIf(JsonNode::isTextual)?.asText()
            ?: throw InstagramEmbedException("Instagram embed context is missing")

        return try {
            objectMapper.readTree(contextJson)
        } catch (error: Exception) {
            throw InstagramEmbedException("Instagram embed context is invalid", error)
        }
    }

    private fun parseMediaUri(value: String): URI {
        val uri = try {
            URI.create(value)
        } catch (error: IllegalArgumentException) {
            throw InstagramEmbedException("Instagram media URL is invalid", error)
        }
        val host = uri.host?.lowercase()
        if (uri.scheme != "https" || host == null || !isInstagramCdnHost(host)) {
            throw InstagramEmbedException("Instagram media URL has unsupported origin")
        }
        return uri
    }

    private fun extractShortcode(url: String): String? {
        val uri = parseUrlOrNull(url.trim()) ?: return null
        if (!isInstagramHost(uri.host)) {
            return null
        }
        val segments = uri.pathSegments()
        if (segments.firstOrNull() !in SUPPORTED_PATH_PREFIXES) {
            return null
        }

        return segments.getOrNull(1)?.takeIf { SHORTCODE_REGEX.matches(it) }
    }

    private fun JsonNode.findFirstText(vararg fieldNames: String): String? {
        if (isObject) {
            for (fieldName in fieldNames) {
                path(fieldName).takeIf(JsonNode::isTextual)?.asText()?.let { return it }
            }
        }

        val children = elements()
        while (children.hasNext()) {
            children.next().findFirstText(*fieldNames)?.let { return it }
        }
        return null
    }

    private fun JsonNode.findFirstInt(vararg fieldNames: String): Int? {
        if (isObject) {
            for (fieldName in fieldNames) {
                path(fieldName).takeIf(JsonNode::isIntegralNumber)?.intValue()?.let { return it }
            }
        }

        val children = elements()
        while (children.hasNext()) {
            children.next().findFirstInt(*fieldNames)?.let { return it }
        }
        return null
    }

    private fun JsonNode.findFirstDecimal(fieldName: String): BigDecimal? {
        if (isObject) {
            path(fieldName).takeIf(JsonNode::isNumber)?.decimalValue()?.let { return it }
        }

        val children = elements()
        while (children.hasNext()) {
            children.next().findFirstDecimal(fieldName)?.let { return it }
        }
        return null
    }

    private fun isInstagramHost(host: String?): Boolean {
        return when (host?.lowercase()) {
            "instagram.com", "www.instagram.com", "m.instagram.com" -> true
            else -> false
        }
    }

    private fun isInstagramCdnHost(host: String): Boolean {
        return host == "cdninstagram.com" ||
                host.endsWith(".cdninstagram.com") ||
                host == "fbcdn.net" ||
                host.endsWith(".fbcdn.net")
    }

    private companion object {
        private val EMBED_PAYLOAD_REGEX = Regex(
            pattern = "\"init\",\\[\\],\\[(.*?)\\]\\],",
            option = RegexOption.DOT_MATCHES_ALL,
        )
        private val SHORTCODE_REGEX = Regex("[A-Za-z0-9_-]+")
        private val SUPPORTED_PATH_PREFIXES = setOf("p", "reel", "reels", "tv")
        private const val CONTEXT_JSON = "contextJSON"
        private const val VIDEO_URL = "video_url"
        private const val VIDEO_DURATION = "video_duration"
        private const val ORIGINAL_WIDTH = "original_width"
        private const val ORIGINAL_HEIGHT = "original_height"
        private const val WIDTH = "width"
        private const val HEIGHT = "height"
        private const val DISPLAY_URL = "display_url"
        private const val THUMBNAIL_URL = "thumbnail_url"
        private const val USERNAME = "username"
    }
}

data class InstagramPreparedDownload(
    val shortcode: String,
    val mediaUri: URI,
    val metadata: YtDlpMetadataDto,
)

class InstagramEmbedException : RuntimeException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}
