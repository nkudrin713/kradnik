package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import org.springframework.stereotype.Component
import java.net.URI

@Component
class YouTubeDownloadHandler : PlatformDownloadHandler {

    override val platform: DownloadPlatform = DownloadPlatform.YOUTUBE

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isYouTubeHost(uri.host)
    }

    override fun resolve(url: String): PlatformDownloadSpecs {
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
                cacheKey = "youtube:video:$youtubeVideoId:video:youtube_h264_mobile_2gb",
                outputType = OutputType.VIDEO,
                platform = platform,
                presetName = "youtube_h264_mobile_2gb",
                formatSelector =
                    "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                            "b[height<=1280]/best",
                extraArgs = listOf("--merge-output-format", "mp4"),
            ),
            audio = DownloadSpec(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = "youtube:video:$youtubeVideoId:audio:youtube_audio",
                outputType = OutputType.AUDIO,
                platform = platform,
                presetName = "youtube_audio",
                formatSelector = "ba/bestaudio",
                extraArgs = listOf(
                    "-x",
                    "--audio-format", "mp3",
                    "--embed-metadata",
                    "--embed-thumbnail",
                    "--convert-thumbnails", "jpg"
                ),
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
}
