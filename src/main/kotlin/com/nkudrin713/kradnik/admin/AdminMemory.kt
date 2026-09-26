package com.nkudrin713.kradnik.admin

import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Instant

/** Reads existing JVM counters; never requests GC, a heap dump or an object histogram. */
class AdminMemoryProbe(private val cgroup: CgroupMemory = CgroupMemory()) {
    private val memory = ManagementFactory.getMemoryMXBean()
    private val pools = ManagementFactory.getMemoryPoolMXBeans()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()

    fun read(at: Instant): MemoryPoint {
        val heap = memory.heapMemoryUsage
        val container = cgroup.read()
        fun poolBytes(matches: (String) -> Boolean): Long? = pools.filter { matches(it.name) }.mapNotNull { it.usage?.used }.takeIf { it.isNotEmpty() }?.sum()
        fun gcTotal(value: (java.lang.management.GarbageCollectorMXBean) -> Long): Long? = collectors.map(value).takeIf { it.isNotEmpty() && it.all { count -> count >= 0 } }?.sum()
        return MemoryPoint(
            at, heap.used, heap.committed, heap.max.takeIf { it > 0 }, memory.nonHeapMemoryUsage.used,
            poolBytes { it == "Metaspace" }, poolBytes { it.startsWith("Code") }, container.used, container.limit,
            gcTotal { it.collectionCount }, gcTotal { it.collectionTime },
        )
    }
}

/** Called only by the existing collector. Retains at most an hour of minute samples. */
class AdminMemory(
    private val probe: AdminMemoryProbe,
    private val store: AdminStatisticsStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val history = sortedMapOf<Long, MemoryPoint>()
    private val pending = sortedMapOf<Long, MemoryPoint>()
    private val gc = ArrayDeque<MemoryPoint>()
    private var lastSample = Instant.MIN
    private var lastPersistence = Instant.MIN
    private var loaded = false
    private var failed = false
    private var persistenceFailed = false
    private var view = MemoryView()

    fun collect(): MemoryView {
        val now = clock.instant()
        if (lastSample == Instant.MIN || !now.isBefore(lastSample.plusSeconds(5))) {
            lastSample = now
            try {
                val raw = probe.read(now)
                gc.addLast(raw)
                while (gc.size > 1 && !gc[1].at.isAfter(now.minusSeconds(60))) gc.removeFirst()
                // A long collection gap is not presented as a one-minute GC measurement.
                while (gc.size > 1 && gc.first().at.isBefore(now.minusSeconds(70))) gc.removeFirst()
                val baseline = gc.first()
                val seconds = now.epochSecond - baseline.at.epochSecond
                fun delta(current: Long?, before: Long?): Long? = if (seconds > 0 && current != null && before != null && current >= before) current - before else null
                val point = raw.copy(gcCount = delta(raw.gcCount, baseline.gcCount), gcTimeMillis = delta(raw.gcTimeMillis, baseline.gcTimeMillis), gcWindowSeconds = seconds)
                history[now.epochSecond / 60] = point
                pending[now.epochSecond / 60] = point
                view = view.copy(current = point, available = true)
                failed = false
            } catch (error: Exception) {
                if (!failed) logger.warn("Admin memory sample unavailable", error)
                failed = true
                view = view.copy(available = false)
            }
        }
        history.entries.removeIf { it.value.at.isBefore(now.minusSeconds(3600)) }
        pending.entries.removeIf { it.value.at.isBefore(now.minusSeconds(3600)) }
        if (lastPersistence == Instant.MIN || !now.isBefore(lastPersistence.plusSeconds(60))) {
            lastPersistence = now
            persist(now.epochSecond / 60)
        }
        return view.copy(history = history.values.toList())
    }

    private fun persist(beforeMinute: Long) {
        try {
            if (!loaded) {
                store.memoryHistory().forEach { history.putIfAbsent(it.at.epochSecond / 60, it) }
                loaded = true
            }
            val completed = pending.filterKeys { it < beforeMinute }
            store.saveMemoryHistory(completed.values.toList())
            completed.keys.forEach(pending::remove)
            view = view.copy(historyAvailable = true)
            persistenceFailed = false
        } catch (error: Exception) {
            if (!persistenceFailed) logger.warn("Admin memory history persistence unavailable", error)
            persistenceFailed = true
            view = view.copy(historyAvailable = false)
        }
    }

    fun flush() = persist(Long.MAX_VALUE)
}

data class MemoryView(val current: MemoryPoint? = null, val history: List<MemoryPoint> = emptyList(), val available: Boolean = false, val historyAvailable: Boolean = false)

data class MemoryPoint(
    val at: Instant,
    val heapUsed: Long,
    val heapCommitted: Long,
    val heapMax: Long?,
    val nonHeapUsed: Long,
    val metaspaceUsed: Long?,
    val codeCacheUsed: Long?,
    val containerUsed: Long?,
    val containerLimit: Long?,
    val gcCount: Long?,
    val gcTimeMillis: Long?,
    val gcWindowSeconds: Long = 0,
)
