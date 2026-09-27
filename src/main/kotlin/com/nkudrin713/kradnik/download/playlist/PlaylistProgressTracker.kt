package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.processing.PlaylistProgress

/** Owned by one attempt; serialized updates cannot publish an older count after a newer one. */
class PlaylistProgressTracker(
    private val entries: List<PlaylistAudioEntry>,
    private val progress: JobProgress,
    completed: List<PlaylistAudioResult> = emptyList(),
    private val downloads: List<PlaylistAudioEntry> = entries,
) {
    private val active = linkedMapOf<Int, PlaylistAudioEntry>()
    private var successful = completed.count { it.fileId != null }
    private var failed = completed.size - successful
    private var warned = false

    @Synchronized
    fun downloading() {
        if (warned) return
        if (downloads.size < 20 && downloads.sumOf { (it.durationSeconds ?: 0).toLong() } < 3600) return
        warned = true
        emit(longWait = true)
    }

    @Synchronized
    fun started(entry: PlaylistAudioEntry) {
        active[entry.position] = entry
        emit()
    }

    @Synchronized
    fun finished(entry: PlaylistAudioEntry, success: Boolean) {
        active.remove(entry.position)
        if (success) successful++ else failed++
        emit()
    }

    private fun emit(longWait: Boolean = false) {
        progress.playlist(PlaylistProgress(entries.size, successful, failed, active.values.sortedBy { it.position }, longWait))
    }
}
