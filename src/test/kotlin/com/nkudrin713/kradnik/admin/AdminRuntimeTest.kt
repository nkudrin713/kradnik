package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.observability.RuntimeError
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRuntimeTest {
    @Test
    fun workerStateClearsJobPhaseAndItemsAfterWork() {
        val runtime = AdminRuntime(true)
        runtime.register("playlist-1", "playlist")
        runtime.busy("playlist-1", 42, "YOUTUBE")
        runtime.phase(42, DownloadPhase.DOWNLOADING)
        runtime.playlist(42, 3)
        runtime.item(42, true)
        runtime.item(42, true)
        assertEquals(2, runtime.snapshot().workers.single().activeItems)
        assertEquals(1, runtime.snapshot().workers.single().waitingItems)
        runtime.item(42, false)
        runtime.state("playlist-1", "IDLE")
        val worker = runtime.snapshot().workers.single()
        assertEquals("IDLE", worker.state)
        assertNull(worker.jobId)
        assertNull(worker.phase)
        assertEquals(0, worker.activeItems)
        assertEquals(0, worker.waitingItems)
    }

    @Test
    fun minuteWindowsExpireAndRingWrapsWithoutResurrectingOldErrors() {
        val clock = AdminTestClock()
        val runtime = AdminRuntime(true, clock)
        runtime.error(RuntimeError.METADATA)
        clock.advance(15 * 60)
        assertEquals(0L, runtime.snapshot().errors[0].counts["METADATA"])
        assertEquals(1L, runtime.snapshot().errors[1].counts["METADATA"])
        clock.advance(1441 * 60)
        runtime.error(RuntimeError.PLAYLIST_ITEM)
        runtime.snapshot().errors.forEach {
            assertEquals(0L, it.counts["METADATA"])
            assertEquals(1L, it.counts["PLAYLIST_ITEM"])
        }
    }

    @Test
    fun concurrentFailuresAreNotLostAndMetadataSlotsAreReusable() {
        val runtime = AdminRuntime(true)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(1000) { pool.submit { runtime.error(RuntimeError.WORKER) } }
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertEquals(1000L, runtime.snapshot().errors.first().counts["WORKER"])
        runtime.register("metadata-1", "metadata")
        runtime.metadataQueue(2)
        assertEquals("metadata-1", runtime.metadataStarted())
        assertEquals(1, runtime.snapshot().metadataQueued)
        runtime.state("metadata-1", "IDLE")
        assertEquals("metadata-1", runtime.metadataStarted())
        assertEquals(0, runtime.snapshot().metadataQueued)
    }

    @Test
    fun disabledMonitoringDoesNotAccumulateState() {
        val runtime = AdminRuntime()
        runtime.register("download-1", "download")
        runtime.busy("download-1", 1, "YOUTUBE")
        runtime.metadataQueue(10)
        runtime.error(RuntimeError.WORKER)
        assertTrue(runtime.snapshot().workers.isEmpty())
        assertEquals(0, runtime.snapshot().metadataQueued)
        assertEquals(0L, runtime.snapshot().errors.first().counts["WORKER"])
    }
}

internal class AdminTestClock(private var now: Instant = Instant.parse("2026-09-26T10:00:00Z")) : Clock() {
    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
}
