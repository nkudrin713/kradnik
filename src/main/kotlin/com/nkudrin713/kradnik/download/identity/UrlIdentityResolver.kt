package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.stereotype.Service
import java.net.URI

@Service
class UrlIdentityResolver {

    fun resolve(
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

        val normalizedUrl = normalizeGeneric(uri)
        return DownloadIdentity(
            originalUrl = originalUrl,
            normalizedUrl = normalizedUrl,
            cacheKey = "generic:${outputType.dbValue}:$preset:$normalizedUrl",
        )
    }

    private fun parseHttpUrl(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { throw UnsupportedUrlException("Invalid URL") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw UnsupportedUrlException("Only HTTP and HTTPS URLs are supported")
        }
        if (uri.host.isNullOrBlank()) {
            throw UnsupportedUrlException("URL host is required")
        }
        return uri
    }

    private fun extractYouTubeVideoId(uri: URI): String? {
        if (!isYouTubeHost(uri.host)) {
            return null
        }

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

    private fun normalizeGeneric(uri: URI): String {
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: ""
        val query = normalizedQuery(uri)
        return "$scheme://$host$port$path$query"
    }

    private fun normalizedQuery(uri: URI): String {
        val query = uri.rawQuery ?: return ""
        val kept = query.split("&")
            .filter { it.isNotBlank() }
            .filterNot { parameter -> TRACKING_PARAMETERS.contains(parameter.substringBefore("=").lowercase()) }
            .sorted()
        return if (kept.isEmpty()) "" else kept.joinToString(prefix = "?", separator = "&")
    }

    private fun extractQueryParameter(uri: URI, name: String): String? {
        return uri.rawQuery
            ?.split("&")
            ?.asSequence()
            ?.map { it.substringBefore("=") to it.substringAfter("=", "") }
            ?.firstOrNull { it.first == name }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun isYouTubeHost(host: String?): Boolean {
        return when (host?.lowercase()) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be" -> true
            else -> false
        }
    }

    private fun URI.pathSegments(): List<String> {
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }

    private companion object {
        private val TRACKING_PARAMETERS = setOf(
            "utm_source",
            "utm_medium",
            "utm_campaign",
            "utm_content",
            "utm_term",
            "fbclid",
            "gclid",
            "yclid",
            "igshid",
            "si",
            "feature",
            "ab_channel",
            "pp",
            "embeds_referring_euri",
            "embeds_referring_origin",
        )
    }
}

class UnsupportedUrlException(message: String) : RuntimeException(message)
