package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.stereotype.Service

@Service
class UrlIdentityResolver(
    private val resolvers: List<PlatformUrlIdentityResolver>,
) {

    fun resolve(
        url: String,
        outputType: OutputType,
        presetName: String?,
    ): DownloadIdentity {
        return resolvers.firstOrNull { it.supports(url) }
            ?.resolve(
                url = url,
                outputType = outputType,
                presetName = presetName,
            )
            ?: throw UnsupportedUrlException("URL is not supported")
    }
}
