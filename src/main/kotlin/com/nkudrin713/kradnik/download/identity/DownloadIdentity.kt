package com.nkudrin713.kradnik.download.identity

data class DownloadIdentity(
    val originalUrl: String,
    val normalizedUrl: String,
    val cacheKey: String,
)
