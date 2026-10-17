package com.nkudrin713.kradnik.download.youtube

import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import java.net.URI

internal data class YouTubeMediaUrl(val original: String, val id: String) {
    val normalized: String get() = "https://www.youtube.com/watch?v=$id"
}

internal fun isYouTubeHost(url: String): Boolean = parseUrlOrNull(url.trim())?.host?.lowercase() in YOUTUBE_HOSTS

internal fun parseYouTubeMediaUrl(url: String): YouTubeMediaUrl {
    val original = url.trim()
    val uri = parseHttpUrl(original)
    val id = extractYouTubeVideoId(uri)
    if (id == null) {
        if (extractQueryParameter(uri, "list") != null) {
            throw UnsupportedUrlException("YouTube playlists are not supported")
        }
        throw UnsupportedUrlException("YouTube URL is not supported")
    }
    return YouTubeMediaUrl(original, id)
}

private fun extractYouTubeVideoId(uri: URI): String? {
    val segments = uri.pathSegments()
    if (uri.host.lowercase() == "youtu.be") {
        return segments.firstOrNull()
    }
    extractQueryParameter(uri, "v")?.let { return it }
    return when (segments.firstOrNull()) {
        "shorts", "live", "embed", "v" -> segments.getOrNull(1)
        else -> null
    }
}

private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
