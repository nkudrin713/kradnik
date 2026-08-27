package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * Evaluates prepared metadata before
 * [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor] starts source transfer.
 * It applies [AudioUploadPlanner] output, rejects known oversize selections, and leaves unknown sizes for the final
 * downloaded-file check; cloud-mode vertical video may continue to [TelegramVideoPreparer][com.nkudrin713.kradnik.download.video.TelegramVideoPreparer].
 */
@Service
class DownloadPreflightService(
    private val audioUploadPlanner: AudioUploadPlanner,
    private val uploadLimits: TelegramUploadLimits,
) {
    /** Returns an adjusted [DownloadPreflightDecision]; unknown source size remains allowed for the final file-size check. */
    fun check(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
    ): DownloadPreflightDecision {
        if (spec.outputType == OutputType.COVER) {
            return DownloadPreflightDecision.Allowed(spec)
        }

        if (spec.outputType == OutputType.AUDIO) {
            when (val plan = audioUploadPlanner.plan(metadata)) {
                is AudioUploadPlan.Allowed -> return DownloadPreflightDecision.Allowed(
                    spec = spec.withAudioQuality(plan.audioQuality),
                )
                is AudioUploadPlan.Rejected -> return DownloadPreflightDecision.Rejected(plan.reason)
                AudioUploadPlan.Unavailable -> Unit
            }
        }

        val selectedSize = selectedSize(metadata) ?: return DownloadPreflightDecision.Allowed(spec)

        if (selectedSize <= uploadLimits.maxUploadBytes) {
            return DownloadPreflightDecision.Allowed(spec)
        }

        if (!uploadLimits.localMode && spec.outputType == OutputType.VIDEO && metadata.isVertical()) {
            return DownloadPreflightDecision.Allowed(spec)
        }

        return DownloadPreflightDecision.Rejected(
            reason = "Selected ${spec.outputType.dbValue} is too large for Telegram: " +
                    "sizeMb=${formatMegabytes(selectedSize)}, limitMb=${formatMegabytes(uploadLimits.maxUploadBytes)}"
        )
    }

    private fun selectedSize(metadata: YtDlpMetadataDto): Long? {
        return metadata.filesize
            ?: metadata.requestedFormats?.totalSize()
            ?: metadata.filesizeApprox
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
        val spec: DownloadSpec,
    ) : DownloadPreflightDecision

    data class Rejected(
        val reason: String,
    ) : DownloadPreflightDecision
}
