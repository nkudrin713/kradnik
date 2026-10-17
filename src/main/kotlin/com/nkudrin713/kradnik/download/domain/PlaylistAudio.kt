package com.nkudrin713.kradnik.download.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.playlist.PlaylistItemFailure

data class PlaylistAudioEntry(
    val position: Int,
    @get:JsonProperty("videoId") @param:JsonProperty("videoId") val sourceId: String,
    val url: String,
    val title: String,
    val durationSeconds: Int?,
    // Old saved playlists have no platform field and belong to YouTube.
    val platform: DownloadPlatform = DownloadPlatform.YOUTUBE,
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
