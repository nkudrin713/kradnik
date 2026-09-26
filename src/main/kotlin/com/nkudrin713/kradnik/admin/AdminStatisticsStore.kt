package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.observability.RuntimeError
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant

/** Persistence adapter for minute aggregates; contains no worker or frontend logic. */
class AdminStatisticsStore(private val jdbc: JdbcTemplate) {
    fun errorsExcept(instanceId: String): List<ErrorMinute> = jdbc.query(
        """
        SELECT minute, kind, sum(count) AS count FROM admin_error_minutes
        WHERE minute >= date_trunc('minute', now()) - interval '24 hours' AND instance_id <> ?
        GROUP BY minute, kind
        """.trimIndent(),
        { rs, _ ->
            RuntimeError.entries.firstOrNull { it.name == rs.getString("kind") }?.let {
                ErrorMinute(rs.getTimestamp("minute").toInstant().epochSecond / 60, it, rs.getLong("count"))
            }
        },
        instanceId,
    ).filterNotNull()

    fun saveErrors(instanceId: String, rows: List<ErrorMinute>) {
        if (rows.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO admin_error_minutes (minute, instance_id, kind, count) VALUES (?, ?, ?, ?)
            ON CONFLICT (minute, instance_id, kind) DO UPDATE SET count = GREATEST(admin_error_minutes.count, EXCLUDED.count)
            """.trimIndent(),
            rows.map { arrayOf(Timestamp.from(Instant.ofEpochSecond(it.minute * 60)), instanceId, it.kind.name, it.count) },
        )
    }

    fun history(): List<HistoryPoint> = jdbc.query(
        "SELECT sampled_at, queued, processing FROM admin_queue_minutes WHERE minute >= now() - interval '1 hour' ORDER BY minute",
    ) { rs, _ -> HistoryPoint(rs.getTimestamp("sampled_at").toInstant(), rs.getLong("queued"), rs.getLong("processing")) }

    fun saveHistory(points: List<HistoryPoint>) {
        if (points.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO admin_queue_minutes (minute, sampled_at, queued, processing)
            VALUES (date_trunc('minute', CAST(? AS timestamptz)), ?, ?, ?)
            ON CONFLICT (minute) DO UPDATE SET sampled_at = EXCLUDED.sampled_at, queued = EXCLUDED.queued, processing = EXCLUDED.processing
            WHERE admin_queue_minutes.sampled_at <= EXCLUDED.sampled_at
            """.trimIndent(),
            points.map { point -> arrayOf(Timestamp.from(point.at), Timestamp.from(point.at), point.queued, point.processing) },
        )
    }

    fun prune() {
        jdbc.update("DELETE FROM admin_error_minutes WHERE minute < now() - interval '7 days'")
        jdbc.update("DELETE FROM admin_queue_minutes WHERE minute < now() - interval '7 days'")
        jdbc.update("DELETE FROM admin_memory_minutes WHERE minute < now() - interval '7 days'")
    }

    fun memoryHistory(): List<MemoryPoint> = jdbc.query(
        "SELECT * FROM admin_memory_minutes WHERE minute >= now() - interval '1 hour' ORDER BY minute",
    ) { rs, _ ->
        fun nullable(name: String): Long? = rs.getLong(name).takeUnless { rs.wasNull() }
        MemoryPoint(
            rs.getTimestamp("sampled_at").toInstant(), rs.getLong("heap_used"), rs.getLong("heap_committed"), nullable("heap_max"),
            rs.getLong("non_heap_used"), nullable("metaspace_used"), nullable("code_cache_used"), nullable("container_used"), nullable("container_limit"),
            nullable("gc_count"), nullable("gc_time_millis"), rs.getLong("gc_window_seconds"),
        )
    }

    fun saveMemoryHistory(points: List<MemoryPoint>) {
        if (points.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO admin_memory_minutes
                (minute, sampled_at, heap_used, heap_committed, heap_max, non_heap_used, metaspace_used, code_cache_used,
                 container_used, container_limit, gc_count, gc_time_millis, gc_window_seconds)
            VALUES (date_trunc('minute', CAST(? AS timestamptz)), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (minute) DO UPDATE SET
                sampled_at = EXCLUDED.sampled_at, heap_used = EXCLUDED.heap_used, heap_committed = EXCLUDED.heap_committed,
                heap_max = EXCLUDED.heap_max, non_heap_used = EXCLUDED.non_heap_used, metaspace_used = EXCLUDED.metaspace_used,
                code_cache_used = EXCLUDED.code_cache_used, container_used = EXCLUDED.container_used, container_limit = EXCLUDED.container_limit,
                gc_count = EXCLUDED.gc_count, gc_time_millis = EXCLUDED.gc_time_millis, gc_window_seconds = EXCLUDED.gc_window_seconds
            WHERE admin_memory_minutes.sampled_at <= EXCLUDED.sampled_at
            """.trimIndent(),
            points,
            points.size,
        ) { statement, p ->
            statement.setTimestamp(1, Timestamp.from(p.at))
            statement.setTimestamp(2, Timestamp.from(p.at))
            listOf(p.heapUsed, p.heapCommitted, p.heapMax, p.nonHeapUsed, p.metaspaceUsed, p.codeCacheUsed, p.containerUsed, p.containerLimit, p.gcCount, p.gcTimeMillis, p.gcWindowSeconds)
                .forEachIndexed { index, value -> statement.setObject(index + 3, value, java.sql.Types.BIGINT) }
        }
    }
}
