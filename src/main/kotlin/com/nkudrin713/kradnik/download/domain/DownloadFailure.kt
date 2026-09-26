package com.nkudrin713.kradnik.download.domain

/** Semantic failures retain the original exception and can be presented without importing individual downloaders. */
open class DownloadFailure(
    val reason: DownloadFailureReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class DownloadFailureReason {
    AUTHENTICATION_REQUIRED,
    SOURCE_UNAVAILABLE,
    SOURCE_RATE_LIMITED,
    SOURCE_REQUEST_FAILED,
    METADATA_UNAVAILABLE,
    SOURCE_FAILED,
    TOO_LARGE,
    PROCESSING_FAILED,
}

class DownloadRejectedException(message: String) : DownloadFailure(DownloadFailureReason.TOO_LARGE, message)
