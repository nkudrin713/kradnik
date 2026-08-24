package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.DownloadSpec

interface PlatformDownloadHandler {

    val platform: DownloadPlatform

    fun supports(url: String): Boolean

    fun resolve(
        url: String,
        outputType: OutputType,
    ): DownloadSpec
}
