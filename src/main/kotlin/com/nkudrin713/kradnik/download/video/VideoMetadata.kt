package com.nkudrin713.kradnik.download.video

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val sampleAspectRatio: String?,
    val displayAspectRatio: String?,
    val containerFormat: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val codecTag: String? = null,
    val codecProfile: String? = null,
    val codecLevel: Int? = null,
    val pixelFormat: String? = null,
    val frameRate: String? = null,
    val colorSpace: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val durationSeconds: Int? = null,
) {
    val isVertical: Boolean = height > width
    val isMp4Container: Boolean = containerFormat
        ?.split(',')
        ?.any { it.equals("mp4", ignoreCase = true) }
        ?: false
}
