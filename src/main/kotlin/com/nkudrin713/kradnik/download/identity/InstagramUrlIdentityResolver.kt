package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI

@Component
@Order(20)
class InstagramUrlIdentityResolver : PlatformUrlIdentityResolver {

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isInstagramHost(uri.host)
    }

    override fun resolve(
        url: String,
        outputType: OutputType,
        presetName: String?,
    ): DownloadIdentity {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val preset = presetName ?: "default"
        val normalizedUrl = normalizeInstagram(uri)
        val mediaKey = extractMediaKey(uri) ?: normalizedUrl

        return DownloadIdentity(
            originalUrl = originalUrl,
            normalizedUrl = normalizedUrl,
            cacheKey = "instagram:$mediaKey:${outputType.dbValue}:$preset",
        )
    }

    private fun normalizeInstagram(uri: URI): String {
        val mediaKey = extractMediaKey(uri) ?: return normalizeGeneric(uri)
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
