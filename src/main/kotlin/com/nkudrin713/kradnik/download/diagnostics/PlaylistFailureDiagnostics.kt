package com.nkudrin713.kradnik.download.diagnostics

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistItemFailure

/** Adapts external diagnostics and old saved results; execution and retry policies use typed failures. */
object PlaylistFailureDiagnostics {
    fun classify(error: String?, reason: DownloadFailureReason? = null): PlaylistItemFailure {
        val text = error.orEmpty().lowercase()
        return when {
            reason == DownloadFailureReason.TOO_LARGE || "max-filesize" in text || "size limit" in text -> PlaylistItemFailure.TOO_LARGE
            text.isDeletedSource() -> PlaylistItemFailure.DELETED
            text.isPrivateSource() -> PlaylistItemFailure.PRIVATE
            text.isGeoBlocked() -> PlaylistItemFailure.GEO_BLOCKED
            reason == DownloadFailureReason.SOURCE_REQUEST_FAILED -> if (text.isNetworkFailure()) PlaylistItemFailure.NETWORK else PlaylistItemFailure.SOURCE
            reason == DownloadFailureReason.SOURCE_RATE_LIMITED -> PlaylistItemFailure.RATE_LIMITED
            reason == DownloadFailureReason.FORMAT_UNAVAILABLE -> PlaylistItemFailure.NO_AUDIO
            reason == DownloadFailureReason.SOURCE_TIMEOUT -> PlaylistItemFailure.TIMEOUT
            reason == DownloadFailureReason.AUTHENTICATION_REQUIRED || "login" in text || "sign in" in text -> PlaylistItemFailure.AUTHENTICATION
            "requested format" in text -> PlaylistItemFailure.NO_AUDIO
            "timed out" in text || "timeout" in text -> PlaylistItemFailure.TIMEOUT
            reason == DownloadFailureReason.SOURCE_RATE_LIMITED || "rate-limited" in text || "try again later" in text -> PlaylistItemFailure.RATE_LIMITED
            text.isNetworkFailure() -> PlaylistItemFailure.NETWORK
            "telegram send failed" in text -> PlaylistItemFailure.UPLOAD
            reason == DownloadFailureReason.SOURCE_UNAVAILABLE || "unavailable" in text -> PlaylistItemFailure.UNAVAILABLE
            reason in setOf(DownloadFailureReason.SOURCE_FAILED, DownloadFailureReason.SOURCE_REQUEST_FAILED, DownloadFailureReason.SOURCE_RATE_LIMITED) -> PlaylistItemFailure.SOURCE
            else -> PlaylistItemFailure.UNKNOWN
        }
    }

    fun normalizeSavedResult(result: PlaylistAudioResult): PlaylistAudioResult {
        if (result.fileId != null || result.failure?.retryable == false) return result
        val diagnostic = classify(result.error)
        val failure = if (diagnostic == PlaylistItemFailure.NETWORK || diagnostic == PlaylistItemFailure.RATE_LIMITED || !diagnostic.retryable) {
            diagnostic
        } else {
            result.failure ?: diagnostic
        }
        return result.copy(failure = failure)
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
