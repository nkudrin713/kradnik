package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec

data class DownloadChoicePlan(
    val mediaInfo: DownloadChoiceMediaInfo,
    val options: List<DownloadChoiceOptionSnapshot>,
)

data class DownloadChoiceMediaInfo(
    val title: String?,
    val durationSeconds: Long?,
    val authorUsername: String? = null,
    val playlistCount: Int? = null,
    val estimatedSizeBytes: Long? = null,
    val audioBitrateKbps: Long? = null,
)

data class DownloadChoiceOptionSnapshot(
    val key: String,
    val label: String,
    val sizeBytes: Long?,
    val approximateSize: Boolean,
    val available: Boolean,
    val unavailableReason: String?,
    val spec: DownloadSpec,
)

class DownloadChoicePlanningException(
    val userMessage: String,
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)
