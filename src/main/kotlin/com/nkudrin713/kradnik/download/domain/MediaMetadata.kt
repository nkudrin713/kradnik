package com.nkudrin713.kradnik.download.domain

import java.math.BigDecimal

/** Metadata used by planning and media preparation, independent of the extraction transport. */
data class MediaMetadata(
    val title: String? = null,
    val thumbnail: String? = null,
    val duration: BigDecimal? = null,
    val width: Int? = null,
    val height: Int? = null,
    val filesize: Long? = null,
    val filesizeApprox: Long? = null,
    val track: String? = null,
    val artist: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val requestedFormats: List<MediaFormat>? = null,
    val formats: List<MediaFormat>? = null,
    val description: String? = null,
    val contentType: MediaContentType = MediaContentType.VIDEO,
)

enum class MediaContentType { VIDEO, IMAGES, CAROUSEL }

data class MediaFormat(
    val formatId: String? = null,
    val ext: String? = null,
    val height: Int? = null,
    val fps: BigDecimal? = null,
    val filesize: Long? = null,
    val filesizeApprox: Long? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val tbr: BigDecimal? = null,
    val vbr: BigDecimal? = null,
    val abr: BigDecimal? = null,
)
