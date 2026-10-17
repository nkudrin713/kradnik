package com.nkudrin713.kradnik.download.domain

enum class PlaylistItemFailure(val retryable: Boolean = true) {
    DELETED(false),
    PRIVATE(false),
    GEO_BLOCKED(false),
    UNAVAILABLE,
    AUTHENTICATION,
    NO_AUDIO,
    TOO_LARGE(false),
    TIMEOUT,
    RATE_LIMITED,
    NETWORK,
    SOURCE,
    UPLOAD,
    UNKNOWN,
    ;

    companion object {
        fun forReason(reason: DownloadFailureReason): PlaylistItemFailure = when (reason) {
            DownloadFailureReason.TOO_LARGE -> TOO_LARGE
            DownloadFailureReason.SOURCE_REQUEST_FAILED, DownloadFailureReason.SOURCE_FAILED -> SOURCE
            DownloadFailureReason.SOURCE_RATE_LIMITED -> RATE_LIMITED
            DownloadFailureReason.FORMAT_UNAVAILABLE -> NO_AUDIO
            DownloadFailureReason.SOURCE_TIMEOUT -> TIMEOUT
            DownloadFailureReason.AUTHENTICATION_REQUIRED -> AUTHENTICATION
            DownloadFailureReason.SOURCE_UNAVAILABLE -> UNAVAILABLE
            else -> UNKNOWN
        }
    }
}
