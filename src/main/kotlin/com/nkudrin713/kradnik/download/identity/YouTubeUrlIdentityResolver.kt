package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI

@Component
@Order(10)
class YouTubeUrlIdentityResolver : PlatformUrlIdentityResolver {

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isYouTubeHost(uri.host)
    }

    override fun resolve(
        url: String,
        outputType: OutputType,
        presetName: String?,
    ): DownloadIdentity {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val youtubeVideoId = extractYouTubeVideoId(uri)
        val preset = presetName ?: "default"

        if (youtubeVideoId != null) {
            val normalizedUrl = "https://www.youtube.com/watch?v=$youtubeVideoId"
            return DownloadIdentity(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = "youtube:video:$youtubeVideoId:${outputType.dbValue}:$preset",
            )
        }

        if (isYouTubeHost(uri.host) && extractQueryParameter(uri, "list") != null) {
            throw UnsupportedUrlException("YouTube playlists are not supported")
        }

        throw UnsupportedUrlException("YouTube URL is not supported")
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
