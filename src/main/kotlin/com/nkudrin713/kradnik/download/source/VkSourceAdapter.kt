package com.nkudrin713.kradnik.download.source

import com.fasterxml.jackson.databind.JsonNode
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.parseVkPostUrl
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.nio.file.Path
import java.time.Duration

@Component
class VkSourceAdapter(private val posts: VkPostClient, private val media: HttpMediaDownloader, private val ytDlp: YtDlpService, private val singles: YtDlpSourceAdapter) : PostSourceAdapter {
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource {
        val url = parseVkPostUrl(request.normalizedUrl) ?: return singles.prepare(request, catalog)
        val item = posts.fetch(url.id)
        val items = mutableListOf<SourcePostItem>()
        val text = mutableListOf<String>()
        collect(item, items, text, 0)
        val postText = text.joinToString("\n\n").takeIf(String::isNotBlank)
        if (items.isEmpty() && postText == null) invalid("VK post contains no supported content")
        val post = SourcePost(items, postText, singleVideoOptions = false)
        return VkPreparedSource(
            MediaMetadata(title = "VK wall${url.id}", description = postText, contentType = if (items.all { it.kind == PostMediaKind.PHOTO }) MediaContentType.IMAGES else MediaContentType.CAROUSEL),
            url.normalized,
            post,
        )
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile = singles.download(request, type, prepared, directory)

    override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
        require(prepared is VkPreparedSource)
        val item = prepared.items[index]
        return when (item.kind) {
            PostMediaKind.PHOTO -> media.download(URI(item.reference), directory.resolve("photo.jpg"), "image/", 10_000_000L, Duration.ofMinutes(10))

            PostMediaKind.VIDEO -> {
                val video = PlatformResolver().resolve(item.reference).video
                ytDlp.download(DownloadRequestMapper.source(video), directory)
            }
        }
    }

    private fun collect(item: JsonNode, items: MutableList<SourcePostItem>, texts: MutableList<String>, depth: Int) {
        if (depth > 5) invalid("VK repost nesting exceeds limit")
        item.path("text").asText("").let(::plainText).takeIf(String::isNotBlank)?.let(texts::add)
        for (attachment in item.path("attachments")) {
            val type = attachment.path("type").asText()
            when (type) {
                "photo" -> {
                    val photo = attachment.path("photo")
                    val sizes = photo.path("sizes").toList() + listOf(photo.path("orig_photo"))
                    val url = sizes.filter { it.path("url").isTextual }.maxByOrNull { it.path("width").asLong() * it.path("height").asLong() }
                        ?.path("url")?.asText() ?: invalid("VK photo URL is missing")
                    val uri = runCatching { URI(url) }.getOrNull() ?: invalid("Invalid VK photo URL")
                    if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.host?.let { it == "userapi.com" || it.endsWith(".userapi.com") } != true) invalid("Unexpected VK photo host")
                    items += SourcePostItem(PostMediaKind.PHOTO, uri.toString())
                }

                "video", "clip" -> {
                    val video = attachment.path(type)
                    val id = "${video.path("owner_id").asText()}_${video.path("id").asText()}"
                    if (!id.matches(Regex("-?[1-9]\\d*_[1-9]\\d*"))) invalid("VK video identity is missing")
                    val access = video.path("access_key").asText("")
                    if (!access.matches(Regex("[A-Za-z0-9_-]*"))) invalid("Invalid VK video access key")
                    items += SourcePostItem(PostMediaKind.VIDEO, "https://vk.com/video$id" + if (access.isNotEmpty()) "?list=$access" else "")
                }
            }
            if (items.size > 20) invalid("VK post exceeds 20 media items")
        }
        for (original in item.path("copy_history")) collect(original, items, texts, depth + 1)
    }

    private fun plainText(value: String): String = HtmlUtils.htmlUnescape(value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")).trim()

    private fun invalid(message: String): Nothing = throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, message)
}

data class VkPreparedSource(override val metadata: MediaMetadata, override val canonicalUrl: String, override val post: SourcePost) : PreparedPostSource {
    val items: List<SourcePostItem> get() = post.items
}
