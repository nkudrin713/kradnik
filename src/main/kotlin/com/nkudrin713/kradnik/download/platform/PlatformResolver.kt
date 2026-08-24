package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.stereotype.Service

@Service
class PlatformResolver(
    private val handlers: List<PlatformDownloadHandler>,
    private val platformFeatureToggles: PlatformFeatureToggles,
) {

    fun resolve(
        url: String,
        outputType: OutputType,
    ): DownloadSpec {
        val handler = handlers.firstOrNull { it.supports(url) }
            ?: throw UnsupportedPlatformException(unsupportedPlatformMessage())

        if (!platformFeatureToggles.isEnabled(handler.platform)) {
            throw UnsupportedPlatformException(unsupportedPlatformMessage())
        }

        return handler.resolve(url, outputType)
    }

    private fun unsupportedPlatformMessage(): String {
        val platforms = platformFeatureToggles.enabledPlatformNames()
            .joinToString(", ")
            .ifBlank { "нет" }
        return "Платформа не поддерживается. Доступные платформы: $platforms."
    }
}
