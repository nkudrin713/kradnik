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
        val allTime = listOf(AllTimeOutcome("completed", 12))
        every { queries.allTimeOutcomes() } returns allTime
        every { queries.jobTrend() } returns listOf(JobDay(java.time.LocalDate.parse("2026-09-26"), 1, 0, 0))
        every { queries.users() } returns UserSummary(1, 0, 0, 1, 1, 1, 1, clock.instant(), clock.instant())
        every { queries.userTrend() } returns emptyList()
        val snapshots = AdminSnapshots(queries, AdminRuntime(true, clock), clock)
        snapshots.collect()
        repeat(1000) { snapshots.snapshot() }
        verify(exactly = 1) { queries.queues() }
        verify(exactly = 1) { queries.outcomes() }
        verify(exactly = 1) { queries.allTimeOutcomes() }
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
        val lastUsers = snapshots.snapshot().users
        every { queries.users() } throws IllegalStateException("database unavailable")
        clock.advance(60)
        snapshots.collect()
        assertFalse(snapshots.snapshot().usersAvailable)
        assertEquals(lastUsers, snapshots.snapshot().users)
        verify(exactly = 1) { queries.allTimeOutcomes() }
        every { queries.allTimeOutcomes() } throws IllegalStateException("count timed out")
        clock.advance(900)
        snapshots.collect()
        assertFalse(snapshots.snapshot().allTimeOutcomesAvailable)
        assertEquals(allTime, snapshots.snapshot().allTimeOutcomes)
        assertEquals(old.allTimeOutcomesUpdatedAt, snapshots.snapshot().allTimeOutcomesUpdatedAt)
        assertTrue(snapshots.snapshot().jobTrendAvailable)
    }

    @Test
    fun historyIsBoundedByTimeAndSize() {
        val clock = AdminTestClock()
        val queries = mockk<AdminQueries>()
        every { queries.queues() } returns emptyList()
        every { queries.outcomes() } returns emptyList()
        every { queries.allTimeOutcomes() } returns emptyList()
        every { queries.jobTrend() } returns emptyList()
        every { queries.users() } returns UserSummary(0, 0, 0, 0, 0, 0, 0, null, null)
        every { queries.userTrend() } returns emptyList()
        val snapshots = AdminSnapshots(queries, AdminRuntime(true, clock), clock)
        repeat(2000) {
            snapshots.collect()
            clock.advance(6)
        }
        assertTrue(snapshots.snapshot().history.size <= 720)
        assertTrue(snapshots.snapshot().history.first().at >= clock.instant().minusSeconds(3606))
    }
}
