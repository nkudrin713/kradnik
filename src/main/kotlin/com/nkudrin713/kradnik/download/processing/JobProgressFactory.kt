package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.progress.DownloadPhase
import com.nkudrin713.kradnik.download.progress.JobNotifications
import com.nkudrin713.kradnik.download.progress.JobProgress
import com.nkudrin713.kradnik.download.progress.PlaylistProgress
import com.nkudrin713.kradnik.observability.BotTelemetry
import org.springframework.stereotype.Component

/** One phase boundary shared by all download workflows; delivery and observation remain independent. */
@Component
class JobProgressFactory(private val notifications: JobNotifications, private val telemetry: BotTelemetry = BotTelemetry.NONE) {
    fun forJob(job: DownloadJob): JobProgress {
        val delivery = notifications.forJob(job)
        return object : JobProgress {
            override fun update(phase: DownloadPhase) {
                telemetry.phase(job.requiredId(), phase)
                delivery.update(phase)
            }

            override fun playlist(progress: PlaylistProgress) = delivery.playlist(progress)
        }
    }
}
