package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.identity.pathSegments

internal data class ThreadsMediaUrl(val original: String, val normalized: String, val id: String, val shared: Boolean)

internal fun isThreadsHost(url: String): Boolean = parseUrlOrNull(url.trim())?.host?.lowercase() in THREADS_HOSTS

internal fun parseThreadsMediaUrl(url: String): ThreadsMediaUrl? {
    val original = url.trim()
    val uri = parseUrlOrNull(original) ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.lowercase() !in THREADS_HOSTS || uri.userInfo != null || uri.port != -1) return null
    val parts = uri.pathSegments()
    val shared = parts.size == 2 && parts[0] == "share"
    val id = when {
        parts.size == 3 && parts[0].matches(Regex("@[A-Za-z0-9._]+")) && parts[1] == "post" -> parts[2]
        parts.size == 2 && parts[0] in setOf("t", "share") -> parts[1]
        else -> return null
    }.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
    return ThreadsMediaUrl(original, "https://www.threads.com/${if (shared) "share" else "t"}/$id/", id, shared)
}

private val THREADS_HOSTS = setOf("threads.com", "www.threads.com", "threads.net", "www.threads.net")
