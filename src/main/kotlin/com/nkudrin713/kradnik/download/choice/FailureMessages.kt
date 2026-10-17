package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.localization.MessageKey

/** Shared semantic failure copy; Telegram statuses adapt these keys for their presentation. */
fun failureMessage(reason: DownloadFailureReason?): MessageKey = when (reason) {
    DownloadFailureReason.TOO_LARGE -> MessageKey.STATUS_REJECTED_TOO_LARGE
    DownloadFailureReason.AUTHENTICATION_REQUIRED -> MessageKey.STATUS_AUTHENTICATION_REQUIRED
    DownloadFailureReason.SOURCE_UNAVAILABLE -> MessageKey.STATUS_SOURCE_UNAVAILABLE
    DownloadFailureReason.FORMAT_UNAVAILABLE -> MessageKey.STATUS_FORMAT_UNAVAILABLE
    DownloadFailureReason.SOURCE_RATE_LIMITED -> MessageKey.STATUS_SOURCE_RATE_LIMITED
    DownloadFailureReason.SOURCE_REQUEST_FAILED -> MessageKey.STATUS_SOURCE_REQUEST_FAILED
    DownloadFailureReason.SOURCE_TIMEOUT -> MessageKey.STATUS_SOURCE_TIMEOUT
    DownloadFailureReason.METADATA_UNAVAILABLE -> MessageKey.ERROR_METADATA_UNAVAILABLE
    else -> MessageKey.STATUS_ERROR
}
