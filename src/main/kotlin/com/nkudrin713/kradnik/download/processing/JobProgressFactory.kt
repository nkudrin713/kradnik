package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.observability.BotTelemetry
import org.springframework.stereotype.Component

/** One phase boundary shared by all download workflows; delivery and observation remain independent. */
@Component
class JobProgressFactory(private val telegram: TelegramJobProgress, private val telemetry: BotTelemetry = BotTelemetry.NONE) {
    fun forJob(job: DownloadJob): JobProgress {
        val delivery = telegram.forJob(job)
        return JobProgress { phase ->
            telemetry.phase(job.requiredId(), phase)
            delivery.update(phase)
        }
    }
}
