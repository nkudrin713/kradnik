package com.nkudrin713.kradnik.download.instagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Path

/**
 * Uses [InstagramHttpClient] to extract metadata and an optional direct video URL from a public embed payload.
 * Direct media is accepted only from HTTPS Instagram CDN origins and is returned with the metadata as
 * [InstagramPreparedDownload] so [DownloadEngine][com.nkudrin713.kradnik.download.DownloadEngine] can choose direct download or yt-dlp fallback.
 */
@Service
class InstagramEmbedDownloader(
    private val httpClient: InstagramHttpClient,
) {
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun prepare(spec: SourceRequest): InstagramPreparedDownload {
        val shortcode = parseInstagramMediaUrl(spec.originalUrl)?.shortcode
            ?: throw InstagramEmbedException("Instagram URL is not supported by embed downloader")
        val embedUri = URI.create("https://www.instagram.com/p/$shortcode/embed/captioned/")
        val html = try {
            httpClient.getText(embedUri)
        } catch (error: InstagramEmbedException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            throw InstagramEmbedException("Instagram embed request failed", error)
        }
        val payload = extractContext(html)
        val context = payload.findValue("shortcode_media") ?: payload.findValue("media") ?: payload
        val children = context.path("edge_sidecar_to_children").path("edges")
        val nodes = if (children.isArray) children.map { it.path("node") } else listOf(context)
        requireItemCount(nodes.size)
        val items = nodes.map { node ->
            if (node.findFirstBoolean(IS_VIDEO) == true || node.findFirstText(VIDEO_URL) != null) {
                InstagramMedia(PostMediaKind.VIDEO, node.findFirstText(VIDEO_URL)?.let(::parseMediaUri))
            } else {
                val url = node.findFirstText(DISPLAY_URL)
                    ?: throw InstagramEmbedException("Instagram photo URL is missing")
                InstagramMedia(PostMediaKind.PHOTO, parseMediaUri(url))
            }
        }
        val mediaUri = items.singleOrNull()?.takeIf { it.kind == PostMediaKind.VIDEO }?.uri
        val mediaSize = mediaUri?.let { httpClient.contentLength(it) }
        val username = context.findFirstText(USERNAME)
        val postText = context.findFirstText(CAPTION)
            ?: context.findFirstNestedText(CAPTION, TEXT)
            ?: context.findFirstNestedText(EDGE_MEDIA_TO_CAPTION, TEXT)

        val preparedDownload = InstagramPreparedDownload(
            shortcode = shortcode,
            mediaUri = mediaUri,
            imageUris = items.filter { it.kind == PostMediaKind.PHOTO }.map { requireNotNull(it.uri) },
            items = items,
            metadata = YtDlpMetadataDto(
                title = context.findFirstText(TITLE)
                    ?: username?.let { "Video by $it" }
                    ?: "Instagram $shortcode",
                thumbnail = context.findFirstText(DISPLAY_URL, THUMBNAIL_URL),
                duration = context.findFirstDecimal(VIDEO_DURATION),
                width = context.findFirstInt(ORIGINAL_WIDTH, WIDTH),
                height = context.findFirstInt(ORIGINAL_HEIGHT, HEIGHT),
                filesize = mediaSize,
                filesizeApprox = null,
                track = null,
                artist = null,
                uploader = username,
                channel = null,
                requestedFormats = null,
                description = postText,
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

    fun prepareFromMetadata(
        spec: SourceRequest,
        metadata: YtDlpMetadataDto,
    ): InstagramPreparedDownload? {
        val entries = metadata.entries.orEmpty().ifEmpty { listOf(metadata) }
        requireItemCount(entries.size)
        val items = entries.map { entry ->
            if (entry.formats.orEmpty().any { it.vcodec != "none" }) {
                // Let yt-dlp select and merge the formats for this one carousel position.
                InstagramMedia(PostMediaKind.VIDEO, null)
            } else {
                val imageUrl = entry.bestImageUrl() ?: return null
                InstagramMedia(PostMediaKind.PHOTO, parseMediaUri(imageUrl))
            }
        }
        val imageUris = items.filter { it.kind == PostMediaKind.PHOTO }.map { requireNotNull(it.uri) }

        val shortcode = parseInstagramMediaUrl(spec.originalUrl)?.shortcode ?: return null
        return InstagramPreparedDownload(
            shortcode = shortcode,
            mediaUri = null,
            imageUris = imageUris,
            items = items,
            metadata = if (items.singleOrNull()?.kind == PostMediaKind.VIDEO) {
                metadata
            } else {
                metadata.copy(
                    thumbnail = imageUris.firstOrNull()?.toString() ?: metadata.thumbnail,
                    duration = null,
                    width = null,
                    height = null,
                    filesize = null,
                    filesizeApprox = null,
                    track = null,
                    artist = null,
                    requestedFormats = null,
                    formats = null,
                    thumbnails = null,
                    entries = null,
                )
            },
        )
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

    suspend fun downloadImages(
        preparedDownload: InstagramPreparedDownload,
        outputDir: Path,
    ): DownloadedFile {
        val imageUris = preparedDownload.imageUris
        require(imageUris.isNotEmpty()) { "Instagram prepared download does not contain image URLs" }
        val files = imageUris.mapIndexed { index, uri ->
            httpClient.downloadImage(
                uri = uri,
                outputFile = outputDir.resolve(
                    "instagram-${preparedDownload.shortcode}-${(index + 1).toString().padStart(2, '0')}.jpg",
                ),
            )
        }
        val totalSize = files.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) }
        logger.info(
            "Instagram images downloaded: shortcode={}, count={}, totalSizeBytes={}",
            preparedDownload.shortcode,
            files.size,
            totalSize,
        )
        return DownloadedFile(
            file = files.first().file,
            sizeBytes = totalSize,
            additionalFiles = files.drop(1).map(DownloadedFile::file),
        )
    }

    suspend fun downloadItem(item: InstagramMedia, outputDir: Path): DownloadedFile {
        val uri = requireNotNull(item.uri) { "Instagram item requires yt-dlp download" }
        return when (item.kind) {
            PostMediaKind.PHOTO -> httpClient.downloadImage(uri, outputDir.resolve("photo.jpg"))
            PostMediaKind.VIDEO -> httpClient.download(uri, outputDir.resolve("video.mp4"))
        }
    }

    private fun requireItemCount(count: Int) {
        if (count !in 1..MAX_IMAGE_COUNT) {
            throw InstagramEmbedException("Instagram post must contain 1 to $MAX_IMAGE_COUNT items")
        }
    }

    private fun YtDlpMetadataDto.bestImageUrl(): String? {
        return thumbnails.orEmpty().asReversed().firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }
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

    private fun JsonNode.findFirstNestedText(parentField: String, childField: String): String? {
        if (isObject) {
            path(parentField).takeIf(JsonNode::isObject)
                ?.findFirstText(childField)
                ?.let { return it }
        }

        val children = elements()
        while (children.hasNext()) {
            children.next().findFirstNestedText(parentField, childField)?.let { return it }
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
        private const val TITLE = "title"
        private const val CAPTION = "caption"
        private const val EDGE_MEDIA_TO_CAPTION = "edge_media_to_caption"
        private const val TEXT = "text"
        private const val MAX_IMAGE_COUNT = 20
    }
}

data class InstagramPreparedDownload(
    val shortcode: String,
    val mediaUri: URI?,
    val imageUris: List<URI> = emptyList(),
    val metadata: YtDlpMetadataDto,
    val items: List<InstagramMedia> = if (imageUris.isNotEmpty()) {
        imageUris.map { InstagramMedia(PostMediaKind.PHOTO, it) }
    } else {
        listOf(InstagramMedia(PostMediaKind.VIDEO, mediaUri))
    },
)

data class InstagramMedia(val kind: PostMediaKind, val uri: URI?)

open class InstagramEmbedException(
    message: String,
    cause: Throwable? = null,
    reason: DownloadFailureReason = DownloadFailureReason.METADATA_UNAVAILABLE,
) : DownloadFailure(reason, message, cause)

class InstagramContentUnavailableException :
    InstagramEmbedException(
        "Instagram content is unavailable without authentication",
        reason = DownloadFailureReason.SOURCE_UNAVAILABLE,
    )
