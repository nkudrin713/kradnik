package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.identity.normalizeGeneric
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI

@Component
@Order(20)
class InstagramDownloadHandler : PlatformDownloadHandler {

    override val platform: DownloadPlatform = DownloadPlatform.INSTAGRAM

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isInstagramHost(uri.host)
    }

    override fun resolve(
        url: String,
        outputType: OutputType,
    ): ResolvedDownload {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val mediaKey = extractMediaKey(uri)
        val normalizedUrl = normalizeInstagram(uri, mediaKey)

        val request = when (outputType) {
            OutputType.VIDEO -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                presetName = "instagram_mobile_video",
                formatSelector =
                    "bv*[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                            "b[height<=1280]/best",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                presetName = "instagram_audio",
                formatSelector = "ba/bestaudio/best",
                extraArgs = listOf("-x", "--audio-format", "mp3"),
            )
        }

        return ResolvedDownload(
            identity = DownloadIdentity(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = "instagram:${mediaKey ?: normalizedUrl}:${outputType.dbValue}:${request.presetName}",
            ),
            request = request,
        )
    }

    private fun normalizeInstagram(uri: URI, mediaKey: String?): String {
        mediaKey ?: return normalizeGeneric(uri)
        val segments = mediaKey.split(":")

        return when (segments.first()) {
            "story" -> "https://www.instagram.com/stories/${segments[1]}/${segments[2]}/"
            else -> "https://www.instagram.com/${segments[0]}/${segments[1]}/"
        }
    }

    private fun extractMediaKey(uri: URI): String? {
        val pathSegments = uri.pathSegments()
        return when (pathSegments.firstOrNull()) {
            "p", "reel", "tv" -> pathSegments.getOrNull(1)?.let { "${pathSegments[0]}:$it" }
            "stories" -> {
                val username = pathSegments.getOrNull(1)
                val storyId = pathSegments.getOrNull(2)
                if (username != null && storyId != null) "story:$username:$storyId" else null
            }
            else -> null
        }
    }

    private fun isInstagramHost(host: String?): Boolean {
        return when (host?.lowercase()) {
            "instagram.com", "www.instagram.com", "m.instagram.com" -> true
            else -> false
        }
    }
}
