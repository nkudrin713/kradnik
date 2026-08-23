package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI

internal const val YOUTUBE_PRESET_PREFIX = "youtube_"

@Component
@Order(10)
class YouTubeDownloadHandler : PlatformDownloadHandler {

    override val platform: DownloadPlatform = DownloadPlatform.YOUTUBE

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isYouTubeHost(uri.host)
    }

    override fun resolve(
        url: String,
        outputType: OutputType,
    ): ResolvedDownload {
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

        val request = when (outputType) {
            OutputType.VIDEO -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                presetName = "youtube_h264_mobile_2gb",
                formatSelector =
                    "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                            "b[height<=1280]/best",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                presetName = "youtube_audio",
                formatSelector = "ba/bestaudio",
                extraArgs = listOf(
                    "-x",
                    "--audio-format", "mp3",
                    "--embed-metadata",
                    "--embed-thumbnail",
                    "--convert-thumbnails", "jpg"
                ),
            )

            OutputType.COVER -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                presetName = "youtube_cover",
                formatSelector = "best",
            )
        }

        return ResolvedDownload(
            identity = DownloadIdentity(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = "youtube:video:$youtubeVideoId:${outputType.dbValue}:${request.presetName}",
            ),
            request = request,
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
