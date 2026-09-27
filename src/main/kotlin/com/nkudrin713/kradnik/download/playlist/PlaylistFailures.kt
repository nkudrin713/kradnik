package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage

enum class PlaylistItemFailure(val message: TelegramMessage) {
    DELETED(TelegramMessage.PLAYLIST_ITEM_DELETED),
    PRIVATE(TelegramMessage.PLAYLIST_ITEM_PRIVATE),
    UNAVAILABLE(TelegramMessage.PLAYLIST_ITEM_UNAVAILABLE),
    AUTHENTICATION(TelegramMessage.PLAYLIST_ITEM_AUTHENTICATION),
    NO_AUDIO(TelegramMessage.PLAYLIST_ITEM_NO_AUDIO),
    TOO_LARGE(TelegramMessage.PLAYLIST_ITEM_TOO_LARGE),
    TIMEOUT(TelegramMessage.PLAYLIST_ITEM_TIMEOUT),
    SOURCE(TelegramMessage.PLAYLIST_ITEM_SOURCE),
    UPLOAD(TelegramMessage.PLAYLIST_ITEM_UPLOAD),
    UNKNOWN(TelegramMessage.PLAYLIST_ITEM_UNKNOWN),
    ;

    companion object {
        fun from(error: String?, reason: DownloadFailureReason? = null): PlaylistItemFailure {
            val text = error.orEmpty().lowercase()
            return when {
                reason == DownloadFailureReason.TOO_LARGE || "max-filesize" in text || "size limit" in text -> TOO_LARGE
                "removed" in text || "deleted" in text -> DELETED
                "private" in text -> PRIVATE
                reason == DownloadFailureReason.AUTHENTICATION_REQUIRED || "login" in text || "sign in" in text -> AUTHENTICATION
                "requested format" in text -> NO_AUDIO
                "timed out" in text || "timeout" in text -> TIMEOUT
                "telegram send failed" in text -> UPLOAD
                reason == DownloadFailureReason.SOURCE_UNAVAILABLE || "unavailable" in text -> UNAVAILABLE
                reason in setOf(DownloadFailureReason.SOURCE_FAILED, DownloadFailureReason.SOURCE_REQUEST_FAILED, DownloadFailureReason.SOURCE_RATE_LIMITED) -> SOURCE
                else -> UNKNOWN
            }
        }
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
