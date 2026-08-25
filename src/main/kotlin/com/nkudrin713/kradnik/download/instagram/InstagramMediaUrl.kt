package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments

internal data class InstagramMediaUrl(
    val original: String,
    val normalized: String,
    val key: String,
    val shortcode: String,
)

internal fun parseInstagramMediaUrl(value: String): InstagramMediaUrl? {
    val original = value.trim()
    val uri = parseUrlOrNull(original) ?: return null
    if (!isInstagramHostName(uri.host)) {
        return null
    }

    val segments = uri.pathSegments()
    val type = segments.firstOrNull()?.takeIf { it in INSTAGRAM_MEDIA_TYPES } ?: return null
    val shortcode = segments.getOrNull(1)?.takeIf(INSTAGRAM_SHORTCODE::matches) ?: return null
    return InstagramMediaUrl(
        original = original,
        normalized = "https://www.instagram.com/$type/$shortcode/",
        key = "$type:$shortcode",
        shortcode = shortcode,
    )
}

internal fun isInstagramHost(value: String): Boolean {
    return isInstagramHostName(parseUrlOrNull(value.trim())?.host)
}

private fun isInstagramHostName(value: String?): Boolean {
    val host = value?.lowercase()
    return host == "instagram.com" || host == "www.instagram.com" || host == "m.instagram.com"
}

private val INSTAGRAM_MEDIA_TYPES = setOf("p", "reel", "reels", "tv")
private val INSTAGRAM_SHORTCODE = Regex("[A-Za-z0-9_-]+")
