package com.nkudrin713.kradnik.download.playlist

data class PlaylistDeliveryResult(val successfulCount: Int, val failedCount: Int, val completion: PlaylistCompletion)

sealed interface PlaylistCompletion {
    data class AudioMessages(val count: Int) : PlaylistCompletion
    data class Archive(val fileId: String) : PlaylistCompletion
}
