package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry

data class PlaylistProgress(val total: Int, val successful: Int, val failed: Int, val active: List<PlaylistAudioEntry>, val longWait: Boolean = false)

fun interface JobProgress {
    fun update(phase: DownloadPhase)
    fun playlist(progress: PlaylistProgress) {}
}

enum class DownloadPhase { DOWNLOADING, PACKING, UPLOADING }
