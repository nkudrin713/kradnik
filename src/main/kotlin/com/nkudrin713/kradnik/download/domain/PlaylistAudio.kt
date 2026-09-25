package com.nkudrin713.kradnik.download.domain

data class PlaylistAudioEntry(
    val position: Int,
    val videoId: String,
    val url: String,
    val title: String,
    val durationSeconds: Int?,
)

data class PlaylistAudioResult(
    val position: Int,
    val fileId: String? = null,
    val error: String? = null,
)
