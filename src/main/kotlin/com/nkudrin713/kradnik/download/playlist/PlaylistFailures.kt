package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistItemFailure
import com.nkudrin713.kradnik.localization.MessageKey

object PlaylistRetrySelection {
    fun entries(entries: List<PlaylistAudioEntry>, results: List<PlaylistAudioResult>): List<PlaylistAudioEntry> {
        val failedByPosition = results.filter { it.fileId == null }.associateBy { it.position }
        return entries.filter { entry -> failedByPosition[entry.position]?.let { (it.failure ?: PlaylistItemFailure.UNKNOWN).retryable } == true }
    }
}

class PlaylistEmptyException(val failures: List<PlaylistAudioResult>) : IllegalStateException("No playlist items could be downloaded")

class PlaylistOperationException(val userMessage: MessageKey, cause: Throwable? = null) : DownloadFailure(DownloadFailureReason.PROCESSING_FAILED, cause?.message ?: userMessage.key, cause)

internal fun DownloadFailure.isPlaylistSourceFailure(): Boolean = reason in setOf(
    DownloadFailureReason.SOURCE_FAILED,
    DownloadFailureReason.AUTHENTICATION_REQUIRED,
    DownloadFailureReason.SOURCE_UNAVAILABLE,
    DownloadFailureReason.SOURCE_RATE_LIMITED,
    DownloadFailureReason.SOURCE_REQUEST_FAILED,
    DownloadFailureReason.SOURCE_TIMEOUT,
    DownloadFailureReason.FORMAT_UNAVAILABLE,
    DownloadFailureReason.METADATA_UNAVAILABLE,
    DownloadFailureReason.TOO_LARGE,
)
