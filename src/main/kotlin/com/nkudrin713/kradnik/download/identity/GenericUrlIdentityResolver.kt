package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Int.MAX_VALUE)
class GenericUrlIdentityResolver : PlatformUrlIdentityResolver {

    override fun supports(url: String): Boolean = true

    override fun resolve(
        url: String,
        outputType: OutputType,
        presetName: String?,
    ): DownloadIdentity {
        val originalUrl = url.trim()
        val uri = parseHttpUrl(originalUrl)
        val preset = presetName ?: "default"
        val normalizedUrl = normalizeGeneric(uri)
        return DownloadIdentity(
            originalUrl = originalUrl,
            normalizedUrl = normalizedUrl,
            cacheKey = "generic:${outputType.dbValue}:$preset:$normalizedUrl",
        )
    }
}
