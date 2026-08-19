package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.request.DownloadRequest

interface PlatformDownloadHandler {

    val platform: DownloadPlatform

    fun supports(url: String): Boolean

    fun resolve(
        url: String,
        outputType: OutputType,
    ): ResolvedDownload
}

data class ResolvedDownload(
    val identity: DownloadIdentity,
    val request: DownloadRequest,
)
