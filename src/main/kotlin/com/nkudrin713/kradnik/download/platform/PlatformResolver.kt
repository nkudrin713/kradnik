package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import org.springframework.stereotype.Service

class UnsupportedPlatformException(message: String) : RuntimeException(message)

@Service
class PlatformResolver(
    private val handlers: List<PlatformDownloadHandler>,
) {
    fun resolve(url: String): PlatformDownloadSpecs {
        val handler = handlers.firstOrNull { it.supports(url) }
            ?: throw UnsupportedPlatformException(unsupportedPlatformMessage())

        return handler.resolve(url)
    }

    private fun unsupportedPlatformMessage(): String {
        val platforms = DownloadPlatform.entries
            .map { it.displayName }
            .joinToString(", ")
        return "Платформа не поддерживается. Доступные платформы: $platforms."
    }
}

interface PlatformDownloadHandler {
    val platform: DownloadPlatform

    fun supports(url: String): Boolean

    fun resolve(url: String): PlatformDownloadSpecs
}

data class PlatformDownloadSpecs(
    val video: DownloadSpec,
    val audio: DownloadSpec,
)
