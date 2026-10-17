package com.nkudrin713.kradnik.download.vk

import com.fasterxml.jackson.databind.JsonNode
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.http.HttpMediaDownloader
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PostSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourcePost
import com.nkudrin713.kradnik.download.source.SourcePostItem
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.download.vk.parseVkPostUrl
import com.nkudrin713.kradnik.ytdlp.YtDlpPreparedSource
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpSelections
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import com.nkudrin713.kradnik.ytdlp.toMediaMetadata
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.nio.file.Path
import java.time.Duration

internal const val VK_VIDEO_PRESET = "vk_mobile_video"
internal const val VK_AUDIO_PRESET = "vk_audio"

@Component
class VkSourceAdapter(private val posts: VkPostClient, private val media: HttpMediaDownloader, private val ytDlp: YtDlpService) : PostSourceAdapter {
    override val platform = DownloadPlatform.VK
    override fun supports(url: String): Boolean = isVkHost(url)
    override fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec = YtDlpSelections.select(spec, selection)

    override fun resolve(url: String): PlatformDownloadSpecs {
        val media = parseVkMediaUrl(url)
        if (media.type == "wall") {
            val spec = DownloadSpec(
                originalUrl = media.original,
                normalizedUrl = media.normalized,
                cacheKey = ResultKeyFactory.source("vk:wall:${media.id}", OutputType.POST, "vk_post"),
                outputType = OutputType.POST,
                platform = DownloadPlatform.VK,
                presetName = "vk_post",
                formatSelector = "best",
            )
            return PlatformDownloadSpecs(spec, spec)
        }

        val cacheKeyPrefix = "vk:${media.type}:${media.id}"
        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = media.original,
                normalizedUrl = media.normalized,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.VIDEO, VK_VIDEO_PRESET),
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.VK,
                presetName = VK_VIDEO_PRESET,
                formatSelector =
                "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a]/" +
                    "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                    "b[height<=1280]/best",
                extraArgs = YtDlpPresets.MERGE_MP4_ARGS,
            ),
            audio = DownloadSpec(
                originalUrl = media.original,
                normalizedUrl = media.normalized,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.AUDIO, VK_AUDIO_PRESET),
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.VK,
                presetName = VK_AUDIO_PRESET,
                formatSelector = YtDlpPresets.AUDIO_FORMAT_WITH_VIDEO_FALLBACK,
                extraArgs = YtDlpPresets.MP3_AUDIO_ARGS,
            ),
        )
    }

    override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource {
        val url = parseVkPostUrl(request.normalizedUrl)
        if (url == null) {
            val metadata = if (catalog) ytDlp.extractCatalogMetadata(request) else ytDlp.extractMetadata(request)
            return YtDlpPreparedSource(metadata.toMediaMetadata())
        }
        val item = posts.fetch(url.id)
        val items = mutableListOf<SourcePostItem>()
        val text = mutableListOf<String>()
        collect(item, items, text, 0)
        val postText = text.joinToString("\n\n").takeIf(String::isNotBlank)
        if (items.isEmpty() && postText == null) invalid("VK post contains no supported content")
        val post = SourcePost(items, postText)
        return VkPreparedSource(
            MediaMetadata(title = "VK wall${url.id}", description = postText, contentType = if (items.all { it.kind == PostMediaKind.PHOTO }) MediaContentType.IMAGES else MediaContentType.CAROUSEL),
            url.normalized,
            post,
        )
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is YtDlpPreparedSource)
        return ytDlp.download(request, directory)
    }

    override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
        require(prepared is VkPreparedSource)
        val item = prepared.items[index]
        return when (item.kind) {
            PostMediaKind.PHOTO -> media.download(URI(item.reference), directory.resolve("photo.jpg"), "image/", 10_000_000L, Duration.ofMinutes(10))

            PostMediaKind.VIDEO -> {
                val video = resolve(item.reference).video
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
