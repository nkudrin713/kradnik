package com.nkudrin713.kradnik.download.platform

import org.springframework.stereotype.Service

@Service
class PlatformResolver(
    private val handlers: List<PlatformDownloadHandler>,
) {

    fun resolve(url: String): PlatformDownloadHandler {
        return handlers.firstOrNull { it.supports(url) }
            ?: throw IllegalStateException("No download handler for url")
    }
}
