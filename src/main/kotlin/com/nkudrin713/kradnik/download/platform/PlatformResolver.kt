package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import com.nkudrin713.kradnik.download.instagram.isInstagramHost
import com.nkudrin713.kradnik.download.instagram.parseInstagramMediaUrl
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal const val VK_VIDEO_PRESET = "vk_mobile_video"
internal const val VK_AUDIO_PRESET = "vk_audio"

class UnsupportedPlatformException(message: String) : RuntimeException(message)

/** Recognizes the three supported sources and builds normalized download specifications. */
@Service
class PlatformResolver {
    fun resolve(url: String): PlatformDownloadSpecs = when {
        supportsYoutube(url) -> resolveYoutube(url)
        supportsInstagram(url) -> resolveInstagram(url)
        supportsVk(url) -> resolveVk(url)
        else -> throw UnsupportedPlatformException("Unsupported platform")
    }

    private fun supportsYoutube(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isYouTubeHost(uri.host)
    }

    private fun resolveYoutube(url: String): PlatformDownloadSpecs {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val youtubeVideoId = extractYouTubeVideoId(uri)
        if (youtubeVideoId == null) {
            if (extractQueryParameter(uri, "list") != null) {
                throw UnsupportedUrlException("YouTube playlists are not supported")
            }
            throw UnsupportedUrlException("YouTube URL is not supported")
        }
        val normalizedUrl = "https://www.youtube.com/watch?v=$youtubeVideoId"

        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = ResultKeyFactory.source("youtube:video:$youtubeVideoId", OutputType.VIDEO, "youtube_h264_mobile_2gb"),
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "youtube_h264_mobile_2gb",
                formatSelector =
                "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                    "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                    "b[height<=1280]/best",
                extraArgs = YtDlpPresets.MERGE_MP4_ARGS,
            ),
            audio = DownloadSpec(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = ResultKeyFactory.source("youtube:video:$youtubeVideoId", OutputType.AUDIO, "youtube_audio"),
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "youtube_audio",
                formatSelector = YtDlpPresets.YOUTUBE_AUDIO_FORMAT,
                extraArgs = YtDlpPresets.YOUTUBE_AUDIO_ARGS,
            ),
        )
    }

    private fun extractYouTubeVideoId(uri: URI): String? {
        val host = uri.host.lowercase()
        val pathSegments = uri.pathSegments()

        if (host == "youtu.be") {
            return pathSegments.firstOrNull()
        }

        extractQueryParameter(uri, "v")?.let { return it }

        return when (pathSegments.firstOrNull()) {
            "shorts", "live", "embed", "v" -> pathSegments.getOrNull(1)
            else -> null
        }
    }

    private fun isYouTubeHost(host: String?): Boolean {
        return when (host?.lowercase()) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be" -> true
            else -> false
        }
    }

    private fun supportsInstagram(url: String): Boolean {
        return isInstagramHost(url)
    }

    private fun resolveInstagram(url: String): PlatformDownloadSpecs {
        val mediaUrl = parseInstagramMediaUrl(url)
            ?: throw UnsupportedUrlException("Instagram URL is not supported")

        val cacheKeyPrefix = "instagram:${mediaUrl.key}"
        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = mediaUrl.original,
                normalizedUrl = mediaUrl.normalized,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.VIDEO, "instagram_mobile_video"),
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.INSTAGRAM,
                presetName = "instagram_mobile_video",
                formatSelector =
                "bv*[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                    "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                    "b[height<=1280]/best",
                extraArgs = YtDlpPresets.MERGE_MP4_ARGS,
            ),
            audio = DownloadSpec(
                originalUrl = mediaUrl.original,
                normalizedUrl = mediaUrl.normalized,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.AUDIO, "instagram_audio"),
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.INSTAGRAM,
                presetName = "instagram_audio",
                formatSelector = YtDlpPresets.AUDIO_FORMAT_WITH_VIDEO_FALLBACK,
                extraArgs = YtDlpPresets.MP3_AUDIO_ARGS,
            ),
        )
    }

    private fun supportsVk(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return VK_HOSTS.contains(uri.host?.lowercase())
    }

    private fun resolveVk(url: String): PlatformDownloadSpecs {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val media = extractMedia(uri)
            ?: throw UnsupportedUrlException("VK URL is not supported")
        val normalizedUrl = "https://vk.com/${media.type}${media.id}"

        val cacheKeyPrefix = "vk:${media.type}:${media.id}"
        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
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
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.AUDIO, VK_AUDIO_PRESET),
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.VK,
                presetName = VK_AUDIO_PRESET,
                formatSelector = YtDlpPresets.AUDIO_FORMAT_WITH_VIDEO_FALLBACK,
                extraArgs = YtDlpPresets.MP3_AUDIO_ARGS,
            ),
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

data class PlatformDownloadSpecs(val video: DownloadSpec, val audio: DownloadSpec)
