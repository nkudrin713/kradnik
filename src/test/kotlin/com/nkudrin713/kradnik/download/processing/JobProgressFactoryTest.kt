package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.observability.BotTelemetry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JobProgressFactoryTest {
    @Test
    fun everyWorkflowPhaseReachesTelemetryIndependentlyOfTelegramDelivery() {
        val phases = mutableListOf<Pair<Long, DownloadPhase>>()
        val telemetry = object : BotTelemetry {
            override fun phase(jobId: Long, phase: DownloadPhase) {
                phases += jobId to phase
            }
        }
        val telegram = mockk<TelegramJobProgress>()
        val delivered = mutableListOf<DownloadPhase>()
        every { telegram.forJob(any()) } returns JobProgress { delivered += it }
        val progress = JobProgressFactory(telegram, telemetry).forJob(DownloadJob(id = 42))
        DownloadPhase.entries.forEach(progress::update)
        assertEquals(DownloadPhase.entries.toList(), delivered)
        assertEquals(DownloadPhase.entries.map { 42L to it }, phases)
        every { telegram.forJob(any()) } returns JobProgress { throw IllegalStateException("delivery failed") }
        assertFailsWith<IllegalStateException> { JobProgressFactory(telegram, telemetry).forJob(DownloadJob(id = 43)).update(DownloadPhase.UPLOADING) }
        assertEquals(43L to DownloadPhase.UPLOADING, phases.last())
    }
}
