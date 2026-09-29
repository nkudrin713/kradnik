package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage

enum class PlaylistItemFailure(val message: TelegramMessage, val retryable: Boolean = true) {
    DELETED(TelegramMessage.PLAYLIST_ITEM_DELETED, false),
    PRIVATE(TelegramMessage.PLAYLIST_ITEM_PRIVATE, false),
    GEO_BLOCKED(TelegramMessage.PLAYLIST_ITEM_GEO_BLOCKED, false),
    UNAVAILABLE(TelegramMessage.PLAYLIST_ITEM_UNAVAILABLE),
    AUTHENTICATION(TelegramMessage.PLAYLIST_ITEM_AUTHENTICATION),
    NO_AUDIO(TelegramMessage.PLAYLIST_ITEM_NO_AUDIO),
    TOO_LARGE(TelegramMessage.PLAYLIST_ITEM_TOO_LARGE, false),
    TIMEOUT(TelegramMessage.PLAYLIST_ITEM_TIMEOUT),
    RATE_LIMITED(TelegramMessage.PLAYLIST_ITEM_RATE_LIMITED),
    NETWORK(TelegramMessage.PLAYLIST_ITEM_NETWORK),
    SOURCE(TelegramMessage.PLAYLIST_ITEM_SOURCE),
    UPLOAD(TelegramMessage.PLAYLIST_ITEM_UPLOAD),
    UNKNOWN(TelegramMessage.PLAYLIST_ITEM_UNKNOWN),
    ;

    companion object {
        fun from(error: String?, reason: DownloadFailureReason? = null): PlaylistItemFailure {
            val text = error.orEmpty().lowercase()
            return when {
                reason == DownloadFailureReason.TOO_LARGE || "max-filesize" in text || "size limit" in text -> TOO_LARGE
                text.isDeletedSource() -> DELETED
                text.isPrivateSource() -> PRIVATE
                text.isGeoBlocked() -> GEO_BLOCKED
                reason == DownloadFailureReason.AUTHENTICATION_REQUIRED || "login" in text || "sign in" in text -> AUTHENTICATION
                "requested format" in text -> NO_AUDIO
                "timed out" in text || "timeout" in text -> TIMEOUT
                reason == DownloadFailureReason.SOURCE_RATE_LIMITED || "rate-limited" in text || "try again later" in text -> RATE_LIMITED
                text.isNetworkFailure() -> NETWORK
                "telegram send failed" in text -> UPLOAD
                reason == DownloadFailureReason.SOURCE_UNAVAILABLE || "unavailable" in text -> UNAVAILABLE
                reason in setOf(DownloadFailureReason.SOURCE_FAILED, DownloadFailureReason.SOURCE_REQUEST_FAILED, DownloadFailureReason.SOURCE_RATE_LIMITED) -> SOURCE
                else -> UNKNOWN
            }
        }

        fun forResult(result: PlaylistAudioResult): PlaylistItemFailure {
            if (result.failure?.retryable == false) return result.failure
            val fromDiagnostic = from(result.error)
            if (fromDiagnostic == NETWORK || fromDiagnostic == RATE_LIMITED) return fromDiagnostic
            return if (!fromDiagnostic.retryable) fromDiagnostic else result.failure ?: fromDiagnostic
        }

        private fun String.isDeletedSource(): Boolean = "video has been removed" in this || "video was removed" in this ||
            "video does not exist" in this || "video has been deleted" in this

        private fun String.isPrivateSource(): Boolean = "private video" in this || "video is private" in this

        private fun String.isGeoBlocked(): Boolean = "not available in your country" in this ||
            "not made this video available in your country" in this || "blocked in your country" in this ||
            "geo-restrict" in this || "not available from your location" in this

        private fun String.isNetworkFailure(): Boolean = "connection reset" in this || "network is unreachable" in this ||
            "temporary failure in name resolution" in this || "http error 502" in this ||
            "http error 503" in this || "http error 504" in this
    }
}

object PlaylistRetrySelection {
    fun entries(entries: List<PlaylistAudioEntry>, results: List<PlaylistAudioResult>): List<PlaylistAudioEntry> {
        val failedByPosition = results.filter { it.fileId == null }.associateBy { it.position }
        return entries.filter { entry -> failedByPosition[entry.position]?.let { PlaylistItemFailure.forResult(it).retryable } == true }
    }
}

class PlaylistEmptyException(val failures: List<PlaylistAudioResult>) : IllegalStateException("No playlist items could be downloaded")

class PlaylistOperationException(val userMessage: TelegramMessage, cause: Throwable? = null) : DownloadFailure(DownloadFailureReason.PROCESSING_FAILED, cause?.message ?: userMessage.key, cause)

internal fun DownloadFailure.isPlaylistSourceFailure(): Boolean = reason in setOf(
    DownloadFailureReason.SOURCE_FAILED,
    DownloadFailureReason.AUTHENTICATION_REQUIRED,
    DownloadFailureReason.SOURCE_UNAVAILABLE,
    DownloadFailureReason.SOURCE_RATE_LIMITED,
    DownloadFailureReason.SOURCE_REQUEST_FAILED,
    DownloadFailureReason.METADATA_UNAVAILABLE,
    DownloadFailureReason.TOO_LARGE,
)
