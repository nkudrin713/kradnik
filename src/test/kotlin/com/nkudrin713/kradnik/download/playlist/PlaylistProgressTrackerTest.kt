package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.processing.PlaylistProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaylistProgressTrackerTest {
    @Test
    fun resumesCountsTracksBothActiveEntriesAndWarnsOnlyOnceForActualDownloads() {
        val events = mutableListOf<PlaylistProgress>()
        val progress = object : JobProgress {
            override fun update(phase: DownloadPhase) {}
            override fun playlist(progress: PlaylistProgress) {
                events += progress
            }
        }
        val entries = (1..3).map { PlaylistAudioEntry(it, "$it", "url", "Title $it", 4000) }
        val tracker = PlaylistProgressTracker(entries, progress, listOf(PlaylistAudioResult(1, failure = PlaylistItemFailure.UNAVAILABLE)), entries.drop(1))
        tracker.started(entries[1])
        tracker.started(entries[2])
        tracker.downloading()
        tracker.downloading()
        assertEquals(1, events.count { it.longWait })
        assertEquals(listOf(2, 3), events.last().active.map { it.position })
        tracker.finished(entries[2], true)
        assertEquals(1, events.last().successful)
        assertEquals(1, events.last().failed)
        assertEquals(listOf(2), events.last().active.map { it.position })
        events.clear()
        val cached = PlaylistProgressTracker(entries, progress, downloads = emptyList())
        cached.started(entries[0])
        cached.downloading()
        assertFalse(events.any { it.longWait })
    }
}
