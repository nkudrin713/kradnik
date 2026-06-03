package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.OutputType

interface PlatformDownloadHandler {

    fun supports(url: String): Boolean

    fun normalize(url: String): String

    fun buildRequest(
        url: String,
        outputType: OutputType,
    ): DownloadRequest
}