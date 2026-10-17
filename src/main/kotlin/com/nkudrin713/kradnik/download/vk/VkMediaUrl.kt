package com.nkudrin713.kradnik.download.vk

import com.nkudrin713.kradnik.download.domain.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseHttpUrl
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class VkMediaUrl(val original: String, val type: String, val id: String) {
    val normalized: String get() = "https://vk.com/$type$id"
}

internal fun isVkHost(url: String): Boolean = parseUrlOrNull(url.trim())?.host?.lowercase() in VK_HOSTS

internal fun parseVkMediaUrl(url: String): VkMediaUrl {
    val original = url.trim()
    parseVkPostUrl(original)?.let { return VkMediaUrl(original, "wall", it.id) }
    val uri = parseHttpUrl(original)
    val media = extractVkMedia(uri) ?: throw UnsupportedUrlException("VK URL is not supported")
    return VkMediaUrl(original, media.groupValues[1], media.groupValues[2])
}

private fun extractVkMedia(uri: URI): MatchResult? {
    VK_MEDIA_PATTERN.matchEntire(uri.path.trim('/'))?.let { return it }
    if (uri.pathSegments().firstOrNull()?.startsWith("wall") == true) {
        return null
    }
    val encodedTarget = extractQueryParameter(uri, "z") ?: return null
    val target = runCatching {
        URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8)
    }.getOrNull() ?: return null
    return VK_QUERY_MEDIA_PATTERN.matchEntire(target)
}

private val VK_HOSTS = setOf(
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
private val VK_MEDIA_PATTERN = Regex("(video|clip)(-?\\d+_\\d+)")
private val VK_QUERY_MEDIA_PATTERN = Regex("(video|clip)(-?\\d+_\\d+)(?:[/?].*)?")
