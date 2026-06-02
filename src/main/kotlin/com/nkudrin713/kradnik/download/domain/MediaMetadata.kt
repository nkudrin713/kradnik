package com.nkudrin713.kradnik.download.domain

data class MediaMetadata(
    val title: String?,
    val extractor: String?,
    val durationSeconds: Long?,
    val width: Int?,
    val height: Int?,
    val webpageUrl: String?,
)
