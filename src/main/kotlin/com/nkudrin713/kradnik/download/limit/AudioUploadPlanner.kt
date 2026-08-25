package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Chooses the highest configured audio bitrate that fits the upload limit with safety margin. */
@Component
class AudioUploadPlanner(
    private val uploadLimits: TelegramUploadLimits,
) {
    fun plan(metadata: YtDlpMetadataDto): AudioUploadPlan {
        val durationSeconds = metadata.duration?.takeIf { it > BigDecimal.ZERO }
            ?: return AudioUploadPlan.Unavailable

        val maxBitrateKbps = BigDecimal.valueOf(targetAudioBytes())
            .multiply(BigDecimal.valueOf(BITS_IN_BYTE))
            .divide(durationSeconds, 0, RoundingMode.DOWN)
            .divide(BigDecimal.valueOf(BITS_IN_KILOBIT), 0, RoundingMode.DOWN)
            .toLong()

        val bitrateKbps = ALLOWED_BITRATES_KBPS.firstOrNull { it <= maxBitrateKbps }
            ?: return AudioUploadPlan.Rejected(
                reason = "Selected audio is too long for Telegram: " +
                        "minQuality=${MIN_AUDIO_QUALITY}, limitMb=${formatMegabytes(uploadLimits.maxUploadBytes)}"
            )

        return AudioUploadPlan.Allowed(
            audioQuality = "${bitrateKbps}K",
            estimatedSizeBytes = estimateSizeBytes(durationSeconds, bitrateKbps),
        )
    }

    private fun estimateSizeBytes(
        durationSeconds: BigDecimal,
        bitrateKbps: Long,
    ): Long {
        return durationSeconds
            .multiply(BigDecimal.valueOf(bitrateKbps))
            .multiply(BigDecimal.valueOf(BITS_IN_KILOBIT))
            .divide(BigDecimal.valueOf(BITS_IN_BYTE), 0, RoundingMode.CEILING)
            .toLong()
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private fun targetAudioBytes(): Long {
        return uploadLimits.maxUploadBytes * TARGET_SIZE_NUMERATOR / TARGET_SIZE_DENOMINATOR
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
        private const val TARGET_SIZE_NUMERATOR = 14L
        private const val TARGET_SIZE_DENOMINATOR = 15L
        private const val BITS_IN_KILOBIT = 1000L
        private const val BITS_IN_BYTE = 8L
        private const val MIN_AUDIO_QUALITY = "32K"
        private val ALLOWED_BITRATES_KBPS = listOf(128L, 96L, 80L, 64L, 48L, 40L, 32L)
    }
}

sealed interface AudioUploadPlan {
    data class Allowed(
        val audioQuality: String,
        val estimatedSizeBytes: Long,
    ) : AudioUploadPlan

    data class Rejected(
        val reason: String,
    ) : AudioUploadPlan

    data object Unavailable : AudioUploadPlan
}
