package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistRetrySelectionTest {
    @Test
    fun separatesExplicitSourceRestrictionsFromTemporaryFailures() {
        assertEquals(PlaylistItemFailure.DELETED, PlaylistItemFailure.from("This video has been removed for violating policy"))
        assertEquals(PlaylistItemFailure.PRIVATE, PlaylistItemFailure.from("Private video. Sign in if you've been granted access"))
        assertEquals(PlaylistItemFailure.GEO_BLOCKED, PlaylistItemFailure.from("The uploader has not made this video available in your country"))
        assertEquals(PlaylistItemFailure.RATE_LIMITED, PlaylistItemFailure.from("Video unavailable. This content isn't available, try again later"))
        assertEquals(PlaylistItemFailure.NETWORK, PlaylistItemFailure.from("Connection reset by peer"))
        assertEquals(PlaylistItemFailure.UNAVAILABLE, PlaylistItemFailure.from("Video unavailable"))
        assertFalse(PlaylistItemFailure.GEO_BLOCKED.retryable)
        assertTrue(PlaylistItemFailure.RATE_LIMITED.retryable)
        assertTrue(PlaylistItemFailure.UNAVAILABLE.retryable)
    }

    @Test
    fun retriesOnlyUncertainOrTemporaryFailuresInOriginalOrder() {
        val entries = (1..7).map { PlaylistAudioEntry(it, "id-$it", "https://youtu.be/id-$it", "Track $it", null) }
        val results = listOf(
            PlaylistAudioResult(1, fileId = "already-sent"),
            PlaylistAudioResult(2, error = "This video has been removed", failure = PlaylistItemFailure.DELETED),
            PlaylistAudioResult(3, error = "Video unavailable. The uploader has not made this video available in your country", failure = PlaylistItemFailure.UNAVAILABLE),
            PlaylistAudioResult(4, error = "Connection reset by peer", failure = PlaylistItemFailure.SOURCE),
            PlaylistAudioResult(5, error = "Video unavailable", failure = PlaylistItemFailure.UNAVAILABLE),
            PlaylistAudioResult(6, error = "max-filesize exceeded", failure = PlaylistItemFailure.TOO_LARGE),
            PlaylistAudioResult(7, error = "requested format is not available", failure = PlaylistItemFailure.NO_AUDIO),
        )

        assertEquals(listOf(4, 5, 7), PlaylistRetrySelection.entries(entries, results).map { it.position })
        assertEquals(PlaylistItemFailure.GEO_BLOCKED, PlaylistItemFailure.forResult(results[2]))
    }
}
