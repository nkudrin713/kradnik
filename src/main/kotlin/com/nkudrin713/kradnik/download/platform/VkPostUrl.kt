package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class VkPostUrl(val id: String) {
    val normalized: String get() = "https://vk.com/wall$id"
}

internal fun parseVkPostUrl(url: String): VkPostUrl? {
    val uri = parseUrlOrNull(url.trim()) ?: return null
    if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host?.lowercase() !in VK_POST_HOSTS || uri.userInfo != null || uri.port != -1) return null
    val path = uri.path.trim('/')
    val target = if (path.startsWith("wall")) {
        path
    } else {
        val encoded = extractQueryParameter(uri, "w") ?: return null
        runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    }
    return VK_POST_PATTERN.matchEntire(target)?.groupValues?.get(1)?.let(::VkPostUrl)
}

private val VK_POST_HOSTS = setOf("vk.com", "m.vk.com", "new.vk.com", "vk.ru", "m.vk.ru", "new.vk.ru")
private val VK_POST_PATTERN = Regex("wall(-?[1-9]\\d*_[1-9]\\d*)")
