package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.request.DownloadRequest

interface PlatformDownloadHandler {

    val platform: DownloadPlatform

    fun supports(url: String): Boolean

    fun normalize(url: String): String

    fun buildRequest(
        url: String,
        outputType: OutputType,
    ): DownloadRequest
}
