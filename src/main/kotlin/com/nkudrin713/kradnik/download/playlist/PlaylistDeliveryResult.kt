package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult

data class PlaylistDeliveryResult(val successfulCount: Int, val failedCount: Int, val completion: PlaylistCompletion, val failures: List<PlaylistAudioResult> = emptyList())

sealed interface PlaylistCompletion {
    data class AudioMessages(val count: Int) : PlaylistCompletion
    data class Archive(val fileId: String) : PlaylistCompletion
}
