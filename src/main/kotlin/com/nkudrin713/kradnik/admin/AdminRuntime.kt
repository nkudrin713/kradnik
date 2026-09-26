package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.observability.BotTelemetry
import com.nkudrin713.kradnik.observability.RuntimeError
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** Bounded process-local telemetry. No I/O is performed on worker threads. */
@Component
class AdminRuntime(
    @Value($$"${admin.enabled:false}") private val enabled: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
    private val statistics: AdminStatistics = AdminStatistics(enabled, clock),
) : BotTelemetry {
    private val startedAt = clock.instant()
    private val workers = sortedMapOf<String, WorkerView>()
    private var metadataQueued = 0

    @Synchronized
    override fun register(id: String, category: String) {
        if (enabled) workers[id] = WorkerView(id, category, "IDLE")
    }

    @Synchronized
    override fun busy(id: String, jobId: Long?, platform: String?) {
        val old = workers[id] ?: return
        workers[id] = old.copy(state = "BUSY", jobId = jobId, platform = platform, since = clock.instant())
    }

    @Synchronized
    override fun state(id: String, state: String) {
        val old = workers[id] ?: return
        workers[id] = WorkerView(id, old.category, state)
    }

    @Synchronized
    override fun phase(jobId: Long, phase: DownloadPhase) {
        val entry = workers.entries.firstOrNull { it.value.jobId == jobId } ?: return
        entry.setValue(entry.value.copy(phase = phase.name))
    }

    @Synchronized
    override fun playlist(jobId: Long, waiting: Int) {
        val entry = workers.entries.firstOrNull { it.value.jobId == jobId } ?: return
        entry.setValue(entry.value.copy(waitingItems = waiting, activeItems = 0))
    }

    @Synchronized
    override fun item(jobId: Long, started: Boolean) {
        val entry = workers.entries.firstOrNull { it.value.jobId == jobId } ?: return
        val old = entry.value
        entry.setValue(old.copy(activeItems = (old.activeItems + if (started) 1 else -1).coerceAtLeast(0), waitingItems = (old.waitingItems - if (started) 1 else 0).coerceAtLeast(0)))
    }

    @Synchronized
    override fun metadataStarted(): String? {
        if (!enabled) return null
        metadataQueued = (metadataQueued - 1).coerceAtLeast(0)
        val slot = workers.values.firstOrNull { it.category == "metadata" && it.state == "IDLE" } ?: return null
        busy(slot.id)
        return slot.id
    }

    @Synchronized
    override fun metadataQueue(delta: Int) {
        if (enabled) metadataQueued = (metadataQueued + delta).coerceAtLeast(0)
    }

    override fun error(kind: RuntimeError) = statistics.error(kind)

    @Synchronized
    fun snapshot(): RuntimeView = RuntimeView(startedAt, workers.values.toList(), metadataQueued, statistics.snapshot())
}

data class WorkerView(
    val id: String,
    val category: String,
    val state: String,
    val jobId: Long? = null,
    val platform: String? = null,
    val phase: String? = null,
    val since: Instant? = null,
    val activeItems: Int = 0,
    val waitingItems: Int = 0,
)

data class RuntimeErrors(val minutes: Int, val counts: Map<String, Long>)
data class RuntimeView(val startedAt: Instant, val workers: List<WorkerView>, val metadataQueued: Int, val errors: List<RuntimeErrors>)
