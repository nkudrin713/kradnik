package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaFormat
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
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
        spec: SingleMediaRequest,
        metadata: MediaMetadata,
    ): DownloadPreflightDecision {
        if (spec.outputType == OutputType.COVER || spec.outputType == OutputType.IMAGES) {
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
                "sizeMb=${formatMegabytes(selectedSize)}, limitMb=${formatMegabytes(uploadLimits.maxUploadBytes)}",
        )
    }

    fun requireAllowed(request: SingleMediaRequest, metadata: MediaMetadata): SingleMediaRequest = when (val decision = check(request, metadata)) {
        is DownloadPreflightDecision.Allowed -> decision.spec
        is DownloadPreflightDecision.Rejected -> throw DownloadRejectedException(decision.reason)
    }

    fun validateFile(file: DownloadedFile) {
        if (file.sizeBytes > uploadLimits.maxUploadBytes) throw DownloadRejectedException("File exceeds Telegram upload limit")
    }

    private fun selectedSize(metadata: MediaMetadata): Long? {
        return metadata.filesize
            ?: metadata.requestedFormats?.totalSize()
            ?: metadata.filesizeApprox
    }

    private fun List<MediaFormat>.totalSize(): Long? {
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

    private fun MediaMetadata.isVertical(): Boolean {
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
        val spec: SingleMediaRequest,
    ) : DownloadPreflightDecision

    data class Rejected(
        val reason: String,
    ) : DownloadPreflightDecision
}
