package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import org.springframework.stereotype.Service

class UnsupportedPlatformException(message: String) : RuntimeException(message)

/**
 * Selects the first supporting [PlatformDownloadHandler] and resolves a URL into canonical video and audio specs.
 * [DownloadChoicePlanner][com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner] consumes both specs so menu
 * choices retain the handler's normalized identity, cache key, selector, and platform-specific arguments.
 */
@Service
class PlatformResolver(
    private val handlers: List<PlatformDownloadHandler>,
) {
    fun resolve(url: String): PlatformDownloadSpecs {
        val handler = handlers.firstOrNull { it.supports(url) }
            ?: throw UnsupportedPlatformException("Unsupported platform")

        return handler.resolve(url)
    }
}

/**
 * Owns URL recognition, normalization, and [DownloadSpec] construction for one [DownloadPlatform].
 * [PlatformResolver] is the only caller that chooses among handler implementations.
 */
interface PlatformDownloadHandler {
    val platform: DownloadPlatform

    fun supports(url: String): Boolean

    fun resolve(url: String): PlatformDownloadSpecs
}

data class PlatformDownloadSpecs(
    val video: DownloadSpec,
    val audio: DownloadSpec,
)
