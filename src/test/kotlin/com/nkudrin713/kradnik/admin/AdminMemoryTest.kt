package com.nkudrin713.kradnik.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminMemoryTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun samplesAreThrottledAndGcWindowDoesNotMixRestoredProcesses() {
        val clock = AdminTestClock()
        val probe = mockk<AdminMemoryProbe>()
        val store = mockk<AdminStatisticsStore>(relaxed = true)
        every { store.memoryHistory() } returns listOf(point(clock.instant().minusSeconds(60)).copy(gcCount = 900))
        every { probe.read(any()) } answers {
            point(firstArg()).copy(gcCount = clock.instant().epochSecond, gcTimeMillis = clock.instant().epochSecond * 10)
        }
        val memory = AdminMemory(probe, store, clock)
        assertNull(memory.collect().current?.gcCount)
        clock.advance(2)
        memory.collect()
        verify(exactly = 1) { probe.read(any()) }
        clock.advance(4)
        assertEquals(6L, memory.collect().current?.gcCount)
        repeat(10) {
            clock.advance(6)
            memory.collect()
        }
        val current = memory.collect().current!!
        assertEquals(60L, current.gcCount)
        assertEquals(600L, current.gcTimeMillis)
        assertEquals(60L, current.gcWindowSeconds)
        clock.advance(120)
        assertNull(memory.collect().current?.gcCount)
    }

    @Test
    fun persistenceRetriesKeepLiveSamplingAndBoundedHistoryAndShutdownFlushesCurrentMinute() {
        val clock = AdminTestClock()
        val probe = mockk<AdminMemoryProbe>()
        every { probe.read(any()) } answers { point(firstArg()) }
        val store = mockk<AdminStatisticsStore>(relaxed = true)
        every { store.memoryHistory() } throws IllegalStateException("offline")
        val memory = AdminMemory(probe, store, clock)
        assertFalse(memory.collect().historyAvailable)
        every { store.memoryHistory() } returns emptyList()
        every { store.saveMemoryHistory(any()) } throws IllegalStateException("offline")
        repeat(650) {
            clock.advance(6)
            memory.collect()
        }
        val offline = memory.collect()
        assertTrue(offline.available)
        assertFalse(offline.historyAvailable)
        assertTrue(offline.history.size <= 61)
        every { store.saveMemoryHistory(any()) } returns Unit
        clock.advance(60)
        assertTrue(memory.collect().historyAvailable)
        verify { store.saveMemoryHistory(match { it.isNotEmpty() && it.size <= 61 }) }
        memory.flush()
        verify { store.saveMemoryHistory(match { it.size == 1 && it.single().at == clock.instant() }) }
        val last = memory.collect().current
        every { probe.read(any()) } throws IllegalStateException("unavailable")
        clock.advance(6)
        val failed = memory.collect()
        assertFalse(failed.available)
        assertEquals(last, failed.current)
    }

    @Test
    fun actualJvmCountersAreAvailableWithoutContainer() {
        val probe = AdminMemoryProbe(CgroupMemory(temp))
        val sample = probe.read(Instant.now())
        assertTrue(sample.heapUsed >= 0)
        assertTrue(sample.heapCommitted >= sample.heapUsed)
        assertTrue(sample.nonHeapUsed > 0)
        assertNotNull(sample.metaspaceUsed)
        assertNotNull(sample.codeCacheUsed)
        assertNull(sample.containerUsed)
    }

    @Test
    fun v2UsesProcessCgroupAndHandlesUnlimitedMissingAndMalformedCounters() {
        Files.writeString(temp.resolve("cgroup"), "0::/tenant/bot\n")
        val mount = Files.createDirectories(temp.resolve("mount"))
        val group = Files.createDirectories(mount.resolve("bot"))
        Files.writeString(temp.resolve("mountinfo"), "1 0 0:1 /tenant $mount rw - cgroup2 cgroup rw\n")
        Files.writeString(group.resolve("memory.current"), "12345\n")
        Files.writeString(group.resolve("memory.max"), "65536\n")
        val reader = CgroupMemory(temp)
        assertEquals(ContainerMemory(12345, 65536), reader.read())
        Files.writeString(group.resolve("memory.max"), "max\n")
        assertEquals(ContainerMemory(12345, null), reader.read())
        Files.writeString(group.resolve("memory.current"), "invalid")
        assertNull(reader.read().used)
        Files.delete(group.resolve("memory.current"))
        assertNull(reader.read().used)
    }

    @Test
    fun v1HybridMountUsesMemoryControllerAndRecognizesUnlimitedSentinel() {
        Files.writeString(temp.resolve("cgroup"), "0::/\n5:cpu:/other\n7:memory:/bot\n")
        val mount = Files.createDirectories(temp.resolve("memory mount"))
        val escapedMount = mount.toString().replace(" ", "\\040")
        Files.writeString(temp.resolve("mountinfo"), "1 0 0:1 /bot $escapedMount rw - cgroup cgroup rw,memory\n")
        Files.writeString(mount.resolve("memory.usage_in_bytes"), "456\n")
        Files.writeString(mount.resolve("memory.limit_in_bytes"), "9223372036854771712\n")
        val reader = CgroupMemory(temp)
        assertEquals(ContainerMemory(456, null), reader.read())
        Files.writeString(mount.resolve("memory.limit_in_bytes"), "1024")
        assertEquals(ContainerMemory(456, 1024), reader.read())
    }

    private fun point(at: Instant): MemoryPoint = MemoryPoint(at, 100, 200, 300, 40, 20, 10, null, null, 1, 2)
}
