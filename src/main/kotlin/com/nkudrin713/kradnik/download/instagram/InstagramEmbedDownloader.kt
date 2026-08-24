package com.nkudrin713.kradnik.download.instagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Path

@Service
class InstagramEmbedDownloader(
    private val httpClient: InstagramHttpClient,
) {
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun prepare(spec: DownloadSpec): InstagramPreparedDownload {
        val shortcode = parseInstagramMediaUrl(spec.originalUrl)?.shortcode
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
        val mediaUri = context.findFirstText(VIDEO_URL)?.let(::parseMediaUri)
        if (mediaUri == null && context.findFirstBoolean(IS_VIDEO) != true) {
            throw InstagramEmbedException("Instagram embed response does not contain video")
        }
        val mediaSize = mediaUri?.let { httpClient.contentLength(it) }

        val preparedDownload = InstagramPreparedDownload(
            shortcode = shortcode,
            mediaUri = mediaUri,
            metadata = YtDlpMetadataDto(
                title = "Instagram $shortcode",
                extractor = "instagram:embed",
                thumbnail = context.findFirstText(DISPLAY_URL, THUMBNAIL_URL),
                duration = context.findFirstDecimal(VIDEO_DURATION),
                width = context.findFirstInt(ORIGINAL_WIDTH, WIDTH),
                height = context.findFirstInt(ORIGINAL_HEIGHT, HEIGHT),
                filesize = mediaSize,
                filesizeApprox = null,
                track = null,
                artist = null,
                uploader = context.findFirstText(USERNAME),
                channel = null,
                requestedFormats = null,
            ),
        )
        logger.info(
            "Instagram embed prepared: shortcode={}, mediaPresent={}, mediaHost={}, width={}, height={}, duration={}",
            shortcode,
            mediaUri != null,
            mediaUri?.host,
            preparedDownload.metadata.width,
            preparedDownload.metadata.height,
            preparedDownload.metadata.duration,
        )
        return preparedDownload
    }

    suspend fun download(
        preparedDownload: InstagramPreparedDownload,
        outputDir: Path,
    ): DownloadedFile {
        val mediaUri = requireNotNull(preparedDownload.mediaUri) {
            "Instagram prepared download does not contain media URL"
        }
        val downloadedFile = httpClient.download(
            uri = mediaUri,
            outputFile = outputDir.resolve("instagram-${preparedDownload.shortcode}.mp4"),
        )
        logger.info(
            "Instagram media downloaded: shortcode={}, mediaHost={}, sizeBytes={}",
            preparedDownload.shortcode,
            mediaUri.host,
            downloadedFile.sizeBytes,
        )
        return downloadedFile
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
        val contextNode = payloadNode.path(CONTEXT_JSON)
        if (contextNode.isNull) {
            throw InstagramContentUnavailableException()
        }
        val contextJson = contextNode.takeIf(JsonNode::isTextual)?.asText()
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

    private fun JsonNode.findFirstBoolean(fieldName: String): Boolean? {
        if (isObject) {
            path(fieldName).takeIf(JsonNode::isBoolean)?.booleanValue()?.let { return it }
        }

        val children = elements()
        while (children.hasNext()) {
            children.next().findFirstBoolean(fieldName)?.let { return it }
        }
        return null
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
        private const val CONTEXT_JSON = "contextJSON"
        private const val VIDEO_URL = "video_url"
        private const val IS_VIDEO = "is_video"
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
    val mediaUri: URI?,
    val metadata: YtDlpMetadataDto,
)

open class InstagramEmbedException : RuntimeException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}

class InstagramContentUnavailableException :
    InstagramEmbedException("Instagram content is unavailable without authentication")
