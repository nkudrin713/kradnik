package com.nkudrin713.kradnik.download.platform

import org.springframework.stereotype.Service

@Service
class PlatformResolver(
    private val handlers: List<PlatformDownloadHandler>,
    private val platformFeatureToggles: PlatformFeatureToggles,
) {

    fun resolve(url: String): PlatformDownloadHandler {
        val handler = handlers.firstOrNull { it.supports(url) }
            ?: throw UnsupportedPlatformException(unsupportedPlatformMessage())

        if (!platformFeatureToggles.isEnabled(handler.platform)) {
            throw UnsupportedPlatformException(unsupportedPlatformMessage())
        }

        return handler
    }

    private fun unsupportedPlatformMessage(): String {
        val platforms = platformFeatureToggles.enabledPlatformNames()
            .joinToString(", ")
            .ifBlank { "нет" }
        return "Платформа не поддерживается. Доступные платформы: $platforms."
    }
}
