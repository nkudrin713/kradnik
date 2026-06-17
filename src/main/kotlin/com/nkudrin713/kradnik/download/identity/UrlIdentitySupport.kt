package com.nkudrin713.kradnik.download.identity

import java.net.URI

internal fun parseHttpUrl(url: String): URI {
    val uri = parseUrlOrNull(url) ?: throw UnsupportedUrlException("Invalid URL")
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw UnsupportedUrlException("Only HTTP and HTTPS URLs are supported")
    }
    if (uri.host.isNullOrBlank()) {
        throw UnsupportedUrlException("URL host is required")
    }
    return uri
}

internal fun parseUrlOrNull(url: String): URI? {
    return runCatching { URI(url) }.getOrNull()
}

internal fun normalizeGeneric(uri: URI): String {
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

internal fun extractQueryParameter(uri: URI, name: String): String? {
    return uri.rawQuery
        ?.split("&")
        ?.asSequence()
        ?.map { it.substringBefore("=") to it.substringAfter("=", "") }
        ?.firstOrNull { it.first == name }
        ?.second
        ?.takeIf { it.isNotBlank() }
}

internal fun URI.pathSegments(): List<String> {
    return path.trim('/').split('/').filter { it.isNotBlank() }
}

private fun normalizedQuery(uri: URI): String {
    val query = uri.rawQuery ?: return ""
    val kept = query.split("&")
        .filter { it.isNotBlank() }
        .filterNot { parameter -> TRACKING_PARAMETERS.contains(parameter.substringBefore("=").lowercase()) }
        .sorted()
    return if (kept.isEmpty()) "" else kept.joinToString(prefix = "?", separator = "&")
}

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
