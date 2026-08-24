package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal const val VK_VIDEO_PRESET = "vk_mobile_video"
internal const val VK_AUDIO_PRESET = "vk_audio"

@Component
@Order(30)
class VkDownloadHandler : PlatformDownloadHandler {

    override val platform: DownloadPlatform = DownloadPlatform.VK

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return VK_HOSTS.contains(uri.host?.lowercase())
    }

    override fun resolve(
        url: String,
        outputType: OutputType,
    ): ResolvedDownload {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val media = extractMedia(uri)
            ?: throw UnsupportedUrlException("VK URL is not supported")
        val normalizedUrl = "https://vk.com/${media.type}${media.id}"

        val request = when (outputType) {
            OutputType.VIDEO -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                strategy = DownloadStrategy.VK_YT_DLP,
                presetName = VK_VIDEO_PRESET,
                formatSelector =
                    "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a]/" +
                            "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                            "b[height<=1280]/best",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                strategy = DownloadStrategy.VK_YT_DLP,
                presetName = VK_AUDIO_PRESET,
                formatSelector = "ba/bestaudio/best",
                extraArgs = listOf("-x", "--audio-format", "mp3"),
            )

            OutputType.COVER -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                strategy = DownloadStrategy.COVER_YT_DLP,
                presetName = "vk_cover",
                formatSelector = "best",
            )
        }

        return ResolvedDownload(
            identity = DownloadIdentity(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = "vk:${media.type}:${media.id}:${outputType.dbValue}:${request.presetName}",
            ),
            request = request,
        )
    }

    private fun extractMedia(uri: URI): VkMedia? {
        VK_MEDIA_PATTERN.matchEntire(uri.path.trim('/'))?.let { return it.toMedia() }

        if (uri.pathSegments().firstOrNull()?.startsWith("wall") == true) {
            return null
        }

        val encodedTarget = extractQueryParameter(uri, "z") ?: return null
        val target = runCatching {
            URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        return VK_QUERY_MEDIA_PATTERN.matchEntire(target)?.toMedia()
    }

    private fun MatchResult.toMedia(): VkMedia {
        return VkMedia(
            type = groupValues[1],
            id = groupValues[2],
        )
    }

    private data class VkMedia(
        val type: String,
        val id: String,
    )

    private companion object {
        val VK_HOSTS = setOf(
            "vk.com",
            "m.vk.com",
            "new.vk.com",
            "vk.ru",
            "m.vk.ru",
            "new.vk.ru",
            "vkvideo.ru",
            "m.vkvideo.ru",
            "new.vkvideo.ru",
            "vksport.vkvideo.ru",
        )
        val VK_MEDIA_PATTERN = Regex("(video|clip)(-?\\d+_\\d+)")
        val VK_QUERY_MEDIA_PATTERN = Regex("(video|clip)(-?\\d+_\\d+)(?:[/?].*)?")
    }
}
