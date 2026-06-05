package com.nkudrin713.kradnik.download.request

import com.nkudrin713.kradnik.download.domain.OutputType

data class DownloadRequest(
    val originalUrl: String,
    val normalizedUrl: String,
    val outputType: OutputType,
    val formatSelector: String,
    val extraArgs: List<String> = emptyList(),
    val presetName: String,
)