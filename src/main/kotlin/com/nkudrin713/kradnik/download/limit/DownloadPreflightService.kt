package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class DownloadPreflightService(
    private val audioUploadPlanner: AudioUploadPlanner,
) {
    fun check(
        request: DownloadRequest,
        metadata: YtDlpMetadataDto,
    ): DownloadPreflightDecision {
        if (request.outputType == OutputType.AUDIO) {
            when (val plan = audioUploadPlanner.plan(metadata)) {
                is AudioUploadPlan.Allowed -> return DownloadPreflightDecision.Allowed(
                    request = request.withAudioQuality(plan.audioQuality),
                )
                is AudioUploadPlan.Rejected -> return DownloadPreflightDecision.Rejected(plan.reason)
                AudioUploadPlan.Unavailable -> Unit
            }
        }

        val selectedSize = selectedSize(metadata) ?: return DownloadPreflightDecision.Allowed(request)

        if (selectedSize <= TelegramUploadLimits.MAX_UPLOAD_BYTES) {
            return DownloadPreflightDecision.Allowed(request)
        }

        if (request.outputType == OutputType.VIDEO && metadata.isVertical()) {
            return DownloadPreflightDecision.Allowed(request)
        }

        return DownloadPreflightDecision.Rejected(
            reason = "Selected ${request.outputType.dbValue} is too large for Telegram: " +
                    "sizeMb=${formatMegabytes(selectedSize)}, limitMb=${formatMegabytes(TelegramUploadLimits.MAX_UPLOAD_BYTES)}"
        )
    }

    private fun selectedSize(metadata: YtDlpMetadataDto): Long? {
        return metadata.filesize
            ?: metadata.filesizeApprox
            ?: metadata.requestedFormats?.totalSize()
    }

    private fun List<YtDlpFormatDto>.totalSize(): Long? {
        if (isEmpty()) {
            return null
        }

        var total = 0L
        for (format in this) {
            val size = format.filesize ?: format.filesizeApprox ?: return null
            total += size
        }

        return total
    }

    private fun YtDlpMetadataDto.isVertical(): Boolean {
        val width = width ?: return false
        val height = height ?: return false
        return height > width
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}

sealed interface DownloadPreflightDecision {
    data class Allowed(
        val request: DownloadRequest,
    ) : DownloadPreflightDecision

    data class Rejected(
        val reason: String,
    ) : DownloadPreflightDecision
}
