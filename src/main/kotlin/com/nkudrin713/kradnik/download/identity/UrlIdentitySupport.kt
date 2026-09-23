package com.nkudrin713.kradnik.download.identity

import java.net.URI

class UnsupportedUrlException(message: String) : RuntimeException(message)

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
