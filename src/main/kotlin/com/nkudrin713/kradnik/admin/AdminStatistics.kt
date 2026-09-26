package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.observability.RuntimeError
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Per-process absolute counters make database retries idempotent, including an ambiguous commit result. */
@Component
class AdminStatistics(
    @Value($$"${admin.enabled:false}") private val enabled: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
    private val store: AdminStatisticsStore? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val instanceId = UUID.randomUUID().toString()
    private val buckets = Array(1441) { ErrorBucket() }
    private var previous = emptyList<ErrorMinute>()
    private var restoreFailed = false
    private var saveFailed = false

    @Volatile
    private var restored = false

    @Volatile
    private var health = StatisticsHealth()

    @PostConstruct
    fun restore() {
        if (!enabled || store == null || restored) return
        try {
            val rows = store.errorsExcept(instanceId)
            synchronized(this) { previous = rows }
            restored = true
            restoreFailed = false
        } catch (error: Exception) {
            if (!restoreFailed) logger.warn("Admin error history could not be restored; the collector will retry", error)
            restoreFailed = true
        }
    }

    @Synchronized
    fun error(kind: RuntimeError) {
        if (!enabled) return
        val minute = clock.instant().epochSecond / 60
        val bucket = buckets[Math.floorMod(minute, buckets.size.toLong()).toInt()]
        if (bucket.minute != minute) {
            bucket.minute = minute
            bucket.counts.fill(0)
            bucket.version = 0
            bucket.savedVersion = 0
        }
        bucket.counts[kind.ordinal]++
        bucket.version++
    }

    @Synchronized
    fun snapshot(): List<RuntimeErrors> {
        val minute = clock.instant().epochSecond / 60
        return listOf(15, 60, 1440).map { window ->
            val counts = LongArray(RuntimeError.entries.size)
            previous.filter { it.minute in (minute - window + 1)..minute }.forEach { row -> counts[row.kind.ordinal] += row.count }
            buckets.filter { it.minute in (minute - window + 1)..minute }.forEach { bucket ->
                bucket.counts.forEachIndexed { index, value -> counts[index] += value }
            }
            RuntimeErrors(window, RuntimeError.entries.associate { it.name to counts[it.ordinal] })
        }
    }

    fun health(): StatisticsHealth = health.copy(restored = restored)

    fun flush() {
        if (!enabled || store == null) return
        if (!restored) restore()
        val pending = synchronized(this) {
            buckets.filter { it.version != it.savedVersion }.map { bucket ->
                Pending(bucket.minute, bucket.version, RuntimeError.entries.filter { bucket.counts[it.ordinal] > 0 }.map { ErrorMinute(bucket.minute, it, bucket.counts[it.ordinal]) })
            }
        }
        try {
            store.saveErrors(instanceId, pending.flatMap { it.rows })
            synchronized(this) {
                pending.forEach { saved ->
                    val bucket = buckets[Math.floorMod(saved.minute, buckets.size.toLong()).toInt()]
                    if (bucket.minute == saved.minute) bucket.savedVersion = saved.version
                }
            }
            health = StatisticsHealth(restored, true, clock.instant())
            saveFailed = false
        } catch (error: Exception) {
            if (!saveFailed) logger.warn("Admin error counters could not be persisted", error)
            saveFailed = true
            health = health.copy(available = false)
        }
    }

    @PreDestroy
    fun stop() = flush()

    private class ErrorBucket(var minute: Long = Long.MIN_VALUE, val counts: LongArray = LongArray(RuntimeError.entries.size), var version: Long = 0, var savedVersion: Long = 0)
    private data class Pending(val minute: Long, val version: Long, val rows: List<ErrorMinute>)
}

data class StatisticsHealth(val restored: Boolean = false, val available: Boolean = false, val updatedAt: Instant? = null)
data class ErrorMinute(val minute: Long, val kind: RuntimeError, val count: Long)
