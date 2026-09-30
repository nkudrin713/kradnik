package com.nkudrin713.kradnik.admin

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A single collector publishes immutable snapshots. Requests never query the database. */
class AdminSnapshots(
    private val queries: AdminQueries,
    private val runtime: AdminRuntime,
    private val clock: Clock = Clock.systemUTC(),
    private val statistics: AdminStatistics? = null,
    private val store: AdminStatisticsStore? = null,
    private val memory: AdminMemory? = null,
    private val version: String = "dev",
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "admin-collector").apply { isDaemon = true } }
    private val history = sortedMapOf<Long, HistoryPoint>()
    private val pendingHistory = sortedMapOf<Long, HistoryPoint>()
    private var historyLoaded = store == null
    private var queuesFailed = false
    private var outcomesFailed = false
    private var allTimeOutcomesFailed = false
    private var usersFailed = false
    private var jobTrendFailed = false
    private var historyFailed = false
    private var lastSlowCollection: Instant = Instant.MIN
    private var lastUserCollection: Instant = Instant.MIN
    private var lastJobTrendCollection: Instant = Instant.MIN
    private var lastAllTimeCollection: Instant = Instant.MIN
    private var lastPrune: Instant = Instant.MIN

    @Volatile
    private var current = DashboardSnapshot(clock.instant(), runtime.snapshot(), version = version)

    @PostConstruct
    fun start() {
        executor.scheduleWithFixedDelay(::collect, 0, 2, TimeUnit.SECONDS)
    }

    fun snapshot(): DashboardSnapshot = current

    internal fun collect() {
        val now = clock.instant()
        var next = current.copy(generatedAt = now, runtime = runtime.snapshot())
        try {
            next = next.copy(queues = queries.queues(), queuesUpdatedAt = clock.instant(), queuesAvailable = true)
            queuesFailed = false
        } catch (error: Exception) {
            if (!queuesFailed) logger.warn("Admin queue snapshot unavailable", error)
            queuesFailed = true
            next = next.copy(queuesAvailable = false)
        }
        if (next.queuesAvailable) {
            val point = HistoryPoint(now, next.queues.filter { it.status == "queued" }.sumOf { it.count }, next.queues.filter { it.status == "processing" }.sumOf { it.count })
            history[now.epochSecond / 60] = point
            pendingHistory[now.epochSecond / 60] = point
        }
        if (lastSlowCollection == Instant.MIN || !now.isBefore(lastSlowCollection.plusSeconds(10))) {
            lastSlowCollection = now
            try {
                next = next.copy(outcomes = queries.outcomes(), outcomesUpdatedAt = clock.instant(), outcomesAvailable = true)
                outcomesFailed = false
            } catch (error: Exception) {
                if (!outcomesFailed) logger.warn("Admin outcome snapshot unavailable", error)
                outcomesFailed = true
                next = next.copy(outcomesAvailable = false)
            }
            statistics?.flush()
            try {
                if (!historyLoaded && store != null) {
                    store.history().forEach { history.putIfAbsent(it.at.epochSecond / 60, it) }
                    historyLoaded = true
                }
                // Repeated writes to a minute replace the sample; gaps are never invented after an outage.
                store?.saveHistory(pendingHistory.values.toList())
                pendingHistory.clear()
                if (store != null && (lastPrune == Instant.MIN || !now.isBefore(lastPrune.plusSeconds(3600)))) {
                    store.prune()
                    lastPrune = now
                }
                next = next.copy(historyAvailable = historyLoaded)
                historyFailed = false
            } catch (error: Exception) {
                if (!historyFailed) logger.warn("Admin queue history persistence unavailable", error)
                historyFailed = true
                next = next.copy(historyAvailable = false)
            }
        }
        if (lastUserCollection == Instant.MIN || !now.isBefore(lastUserCollection.plusSeconds(60))) {
            lastUserCollection = now
            try {
                val users = queries.users()
                val userTrend = queries.userTrend()
                next = next.copy(users = users, userTrend = userTrend, usersUpdatedAt = clock.instant(), usersAvailable = true)
                usersFailed = false
            } catch (error: Exception) {
                if (!usersFailed) logger.warn("Admin user statistics unavailable", error)
                usersFailed = true
                next = next.copy(usersAvailable = false)
            }
        }
        if (lastJobTrendCollection == Instant.MIN || !now.isBefore(lastJobTrendCollection.plusSeconds(60))) {
            lastJobTrendCollection = now
            try {
                next = next.copy(jobTrend = queries.jobTrend(), jobTrendUpdatedAt = clock.instant(), jobTrendAvailable = true)
                jobTrendFailed = false
            } catch (error: Exception) {
                if (!jobTrendFailed) logger.warn("Admin job trend unavailable", error)
                jobTrendFailed = true
                next = next.copy(jobTrendAvailable = false)
            }
        }
        if (lastAllTimeCollection == Instant.MIN || !now.isBefore(lastAllTimeCollection.plusSeconds(900))) {
            lastAllTimeCollection = now
            try {
                next = next.copy(allTimeOutcomes = queries.allTimeOutcomes(), allTimeOutcomesUpdatedAt = clock.instant(), allTimeOutcomesAvailable = true)
                allTimeOutcomesFailed = false
            } catch (error: Exception) {
                if (!allTimeOutcomesFailed) logger.warn("Admin all-time outcomes unavailable", error)
                allTimeOutcomesFailed = true
                next = next.copy(allTimeOutcomesAvailable = false)
            }
        }
        history.entries.removeIf { it.value.at.isBefore(now.minusSeconds(3600)) }
        pendingHistory.entries.removeIf { it.value.at.isBefore(now.minusSeconds(3600)) }
        current = next.copy(history = history.values.toList(), statistics = statistics?.health() ?: StatisticsHealth(), memory = memory?.collect() ?: MemoryView())
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) {
                memory?.flush()
                store?.saveHistory(pendingHistory.values.toList())
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            logger.warn("Final admin queue sample could not be persisted", error)
        }
    }
}

data class DashboardSnapshot(
    val generatedAt: Instant,
    val runtime: RuntimeView,
    val version: String = "dev",
    val queues: List<QueueView> = emptyList(),
    val outcomes: List<OutcomeView> = emptyList(),
    val queuesUpdatedAt: Instant? = null,
    val outcomesUpdatedAt: Instant? = null,
    val queuesAvailable: Boolean = false,
    val outcomesAvailable: Boolean = false,
    val allTimeOutcomes: List<AllTimeOutcome> = emptyList(),
    val allTimeOutcomesUpdatedAt: Instant? = null,
    val allTimeOutcomesAvailable: Boolean = false,
    val users: UserSummary? = null,
    val userTrend: List<UserDay> = emptyList(),
    val jobTrend: List<JobDay> = emptyList(),
    val jobTrendUpdatedAt: Instant? = null,
    val jobTrendAvailable: Boolean = false,
    val usersUpdatedAt: Instant? = null,
    val usersAvailable: Boolean = false,
    val history: List<HistoryPoint> = emptyList(),
    val historyAvailable: Boolean = false,
    val statistics: StatisticsHealth = StatisticsHealth(),
    val memory: MemoryView = MemoryView(),
)

data class HistoryPoint(val at: Instant, val queued: Long, val processing: Long)
