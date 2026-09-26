package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.observability.BotTelemetry
import com.nkudrin713.kradnik.observability.RuntimeError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistItemsTest {
    private val events = RecordingTelemetry()
    private val entries = (1..4).map { PlaylistAudioEntry(it, "$it", "https://example.com/$it", "Track $it", 10) }

    @Test
    fun capacityIncludesItemDeliveryAndWaitingEntriesRemainOutsideActiveSlots() = runTest {
        val release = CompletableDeferred<Unit>()
        val result = async {
            PlaylistItems(2, events).map(1, entries) {
                release.await()
                it.position
            }
        }
        runCurrent()
        assertEquals(2, events.active)
        assertEquals(2, events.waiting)
        release.complete(Unit)
        assertEquals(listOf(1, 2, 3, 4), result.await())
        assertEquals(2, events.maximum)
        assertEquals(0, events.active)
        assertEquals(0, events.waiting)
        assertEquals(0, events.failures)
    }

    @Test
    fun handledAndPropagatedFailuresAreCountedOnceAndAlwaysReleaseSlot() = runTest {
        val items = PlaylistItems(1, events)
        assertEquals(listOf(2, 4), items.map(1, entries) { if (it.position % 2 == 0) it.position else null })
        assertEquals(2, events.failures)
        assertFailsWith<IllegalStateException> { items.map<Int>(1, entries.take(1)) { throw IllegalStateException("failed") } }
        assertEquals(3, events.failures)
        assertEquals(0, events.active)
    }

    @Test
    fun cancellationReleasesActiveSlotsWithoutRecordingFailure() = runTest {
        val result = async { PlaylistItems(2, events).map<Int>(1, entries) { awaitCancellation() } }
        runCurrent()
        assertEquals(2, events.active)
        result.cancelAndJoin()
        assertEquals(0, events.active)
        assertEquals(0, events.failures)
    }

    private class RecordingTelemetry : BotTelemetry {
        var active = 0
        var waiting = 0
        var maximum = 0
        var failures = 0
        override fun playlist(jobId: Long, waiting: Int) {
            this.waiting = waiting
        }
        override fun item(jobId: Long, started: Boolean) {
            active += if (started) 1 else -1
            if (started) waiting--
            maximum = maxOf(maximum, active)
        }
        override fun error(kind: RuntimeError) {
            failures++
        }
    }
}
