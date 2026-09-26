package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.observability.RuntimeError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminStatisticsTest {
    @Test
    fun failedRestoreAndAmbiguousSaveRetryDoNotLoseOrDoubleCountCurrentProcessErrors() {
        val clock = AdminTestClock()
        val store = mockk<AdminStatisticsStore>()
        every { store.errorsExcept(any()) } throws IllegalStateException("database unavailable")
        val statistics = AdminStatistics(true, clock, store)
        statistics.restore()
        statistics.error(RuntimeError.METADATA)
        every { store.saveErrors(any(), any()) } throws IllegalStateException("response lost")
        statistics.flush()
        assertFalse(statistics.health().available)
        val minute = clock.instant().epochSecond / 60
        every { store.errorsExcept(any()) } returns listOf(ErrorMinute(minute, RuntimeError.METADATA, 4))
        every { store.saveErrors(any(), any()) } answers {
            // A concurrent error during I/O must remain pending for the next flush.
            statistics.error(RuntimeError.METADATA)
        }
        statistics.flush()
        assertEquals(6L, statistics.snapshot().first().counts["METADATA"])
        assertTrue(statistics.health().restored)
        assertTrue(statistics.health().available)
        every { store.saveErrors(any(), any()) } returns Unit
        statistics.flush()
        verify { store.saveErrors(any(), listOf(ErrorMinute(minute, RuntimeError.METADATA, 2))) }
        statistics.flush()
        assertEquals(6L, statistics.snapshot().first().counts["METADATA"])
    }

    @Test
    fun shutdownFlushesPendingCountersAndOldMinutesExpire() {
        val clock = AdminTestClock()
        val store = mockk<AdminStatisticsStore>(relaxed = true)
        every { store.errorsExcept(any()) } returns emptyList()
        val statistics = AdminStatistics(true, clock, store)
        statistics.error(RuntimeError.WORKER)
        statistics.stop()
        verify { store.saveErrors(any(), match { it.single().count == 1L }) }
        clock.advance(86400)
        assertEquals(0L, statistics.snapshot().last().counts["WORKER"])
    }
}
