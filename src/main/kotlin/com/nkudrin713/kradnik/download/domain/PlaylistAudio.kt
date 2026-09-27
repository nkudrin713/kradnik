package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.playlist.PlaylistItemFailure

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
    val failure: PlaylistItemFailure? = null,
)

/** Delivery does not change the downloaded media or the playlist queue. */
enum class PlaylistDeliveryMode {
    AUDIO_MESSAGES,
    ZIP,
}
