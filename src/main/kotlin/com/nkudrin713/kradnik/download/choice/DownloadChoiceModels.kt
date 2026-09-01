package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec

data class DownloadChoicePlan(
    val mediaInfo: DownloadChoiceMediaInfo,
    val options: List<DownloadChoiceOptionSnapshot>,
)

data class DownloadChoiceMediaInfo(
    val channelName: String?,
    val title: String?,
    val durationSeconds: Long?,
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
