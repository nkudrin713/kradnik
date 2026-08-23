package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.platform.ResolvedDownload
import com.nkudrin713.kradnik.download.request.DownloadRequest

data class DownloadChoicePlan(
    val originalUrl: String,
    val normalizedUrl: String,
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
    val originalUrl: String,
    val normalizedUrl: String,
    val cacheKey: String,
    val outputType: OutputType,
    val presetName: String,
    val formatSelector: String,
    val extraArgs: List<String>,
) {
    fun toResolvedDownload(): ResolvedDownload {
        return ResolvedDownload(
            identity = DownloadIdentity(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                cacheKey = cacheKey,
            ),
            request = DownloadRequest(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                outputType = outputType,
                formatSelector = formatSelector,
                extraArgs = extraArgs,
                presetName = presetName,
            ),
        )
    }
}

object DownloadSizeFormatter {
    fun format(bytes: Long): String {
        val value = if (bytes >= BYTES_IN_GIGABYTE) {
            bytes / BYTES_IN_GIGABYTE
        } else {
            bytes / BYTES_IN_MEGABYTE
        }
        val unit = if (bytes >= BYTES_IN_GIGABYTE) "ГБ" else "МБ"
        val pattern = if (value >= 100) "%.0f" else if (value >= 10) "%.1f" else "%.2f"
        return "$pattern $unit".format(java.util.Locale.forLanguageTag("ru-RU"), value)
    }

    private const val BYTES_IN_MEGABYTE = 1_000_000.0
    private const val BYTES_IN_GIGABYTE = 1_000_000_000.0
}

class DownloadChoicePlanningException(
    val userMessage: String,
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)
