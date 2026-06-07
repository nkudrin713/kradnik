package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType

interface PlatformUrlIdentityResolver {

    fun supports(url: String): Boolean

    fun resolve(
        url: String,
        outputType: OutputType,
        presetName: String?,
    ): DownloadIdentity
}
