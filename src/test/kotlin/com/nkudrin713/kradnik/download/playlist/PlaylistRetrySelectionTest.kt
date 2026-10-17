package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.diagnostics.PlaylistFailureDiagnostics
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistItemFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistRetrySelectionTest {
    @Test
    fun specificSourceReasonsTakePrecedenceOverLoginHints() {
        assertEquals(PlaylistItemFailure.SOURCE, PlaylistFailureDiagnostics.classify("Sign in to confirm you’re not a bot", DownloadFailureReason.SOURCE_REQUEST_FAILED))
        assertEquals(PlaylistItemFailure.SOURCE, PlaylistFailureDiagnostics.classify("Requested content is not available, rate-limit reached or login required", DownloadFailureReason.SOURCE_REQUEST_FAILED))
        assertEquals(PlaylistItemFailure.RATE_LIMITED, PlaylistFailureDiagnostics.classify("HTTP Error 429. Sign in", DownloadFailureReason.SOURCE_RATE_LIMITED))
        assertEquals(PlaylistItemFailure.NO_AUDIO, PlaylistFailureDiagnostics.classify("Diagnostic truncated", DownloadFailureReason.FORMAT_UNAVAILABLE))
        assertEquals(PlaylistItemFailure.TIMEOUT, PlaylistFailureDiagnostics.classify(null, DownloadFailureReason.SOURCE_TIMEOUT))
    }

    @Test
    fun separatesExplicitSourceRestrictionsFromTemporaryFailures() {
        assertEquals(PlaylistItemFailure.DELETED, PlaylistFailureDiagnostics.classify("This video has been removed for violating policy"))
        assertEquals(PlaylistItemFailure.PRIVATE, PlaylistFailureDiagnostics.classify("Private video. Sign in if you've been granted access"))
        assertEquals(PlaylistItemFailure.GEO_BLOCKED, PlaylistFailureDiagnostics.classify("The uploader has not made this video available in your country"))
        assertEquals(PlaylistItemFailure.RATE_LIMITED, PlaylistFailureDiagnostics.classify("Video unavailable. This content isn't available, try again later"))
        assertEquals(PlaylistItemFailure.NETWORK, PlaylistFailureDiagnostics.classify("Connection reset by peer"))
        assertEquals(PlaylistItemFailure.UNAVAILABLE, PlaylistFailureDiagnostics.classify("Video unavailable"))
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

        val restored = results.map(PlaylistFailureDiagnostics::normalizeSavedResult)
        assertEquals(listOf(4, 5, 7), PlaylistRetrySelection.entries(entries, restored).map { it.position })
        assertEquals(PlaylistItemFailure.GEO_BLOCKED, PlaylistFailureDiagnostics.normalizeSavedResult(results[2]).failure)
    }
}
