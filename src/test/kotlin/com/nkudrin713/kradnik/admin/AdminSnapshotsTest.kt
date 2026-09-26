package com.nkudrin713.kradnik.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSnapshotsTest {
    @Test
    fun requestsShareSnapshotAndFailureRetainsLastSuccessfulDatabaseData() {
        val clock = AdminTestClock()
        val queries = mockk<AdminQueries>()
        val queues = listOf(QueueView("single", "queued", 7, clock.instant()))
        every { queries.queues() } returns queues
        every { queries.outcomes() } returns listOf(OutcomeView(15, "single", "failed", 2))
        val snapshots = AdminSnapshots(queries, AdminRuntime(true, clock), clock)
        snapshots.collect()
        repeat(1000) { snapshots.snapshot() }
        verify(exactly = 1) { queries.queues() }
        verify(exactly = 1) { queries.outcomes() }
        val old = snapshots.snapshot()
        every { queries.queues() } throws IllegalStateException("database unavailable")
        clock.advance(2)
        snapshots.collect()
        val failed = snapshots.snapshot()
        assertFalse(failed.queuesAvailable)
        assertTrue(failed.outcomesAvailable)
        assertEquals(old.queuesUpdatedAt, failed.queuesUpdatedAt)
        assertEquals(queues, failed.queues)
        verify(exactly = 1) { queries.outcomes() }
        clock.advance(8)
        snapshots.collect()
        verify(exactly = 2) { queries.outcomes() }
        every { queries.queues() } returns emptyList()
        clock.advance(2)
        snapshots.collect()
        assertTrue(snapshots.snapshot().queuesAvailable)
        assertTrue(snapshots.snapshot().queues.isEmpty())
    }

    @Test
    fun historyIsBoundedByTimeAndSize() {
        val clock = AdminTestClock()
        val queries = mockk<AdminQueries>()
        every { queries.queues() } returns emptyList()
        every { queries.outcomes() } returns emptyList()
        val snapshots = AdminSnapshots(queries, AdminRuntime(true, clock), clock)
        repeat(2000) {
            snapshots.collect()
            clock.advance(6)
        }
        assertTrue(snapshots.snapshot().history.size <= 720)
        assertTrue(snapshots.snapshot().history.first().at >= clock.instant().minusSeconds(3606))
    }
}
