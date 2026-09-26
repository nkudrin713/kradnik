package com.nkudrin713.kradnik.admin

import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** Queue and job-outcome projections; the rest of the dashboard does not depend on JPA or the job schema. */
class AdminQueries(private val jdbc: JdbcTemplate) {

    fun queues(): List<QueueView> = jdbc.query(
        QUEUES_SQL,
    ) { rs, _ -> QueueView(rs.getString("workload_type"), rs.getString("status"), rs.getLong("jobs"), rs.getTimestamp("oldest").toInstant()) }

    fun outcomes(): List<OutcomeView> = jdbc.query(
        OUTCOMES_SQL,
    ) { rs, _ -> OutcomeView(rs.getInt("minutes"), rs.getString("workload_type"), rs.getString("status"), rs.getLong("jobs")) }
    internal companion object {
        val QUEUES_SQL = """
        SELECT workload_type, status, count(*) AS jobs, min(created_at) AS oldest
        FROM download_jobs WHERE status IN ('queued', 'processing')
        GROUP BY workload_type, status
        """.trimIndent()
        val OUTCOMES_SQL = """
        SELECT w.minutes, j.workload_type, j.status, count(*) AS jobs
        FROM download_jobs j
        CROSS JOIN (VALUES (15), (60), (1440)) AS w(minutes)
        WHERE j.completed_at >= now() - interval '24 hours'
          AND j.completed_at >= now() - w.minutes * interval '1 minute'
          AND j.status IN ('completed', 'failed', 'cancelled_by_user')
        GROUP BY w.minutes, j.workload_type, j.status
        """.trimIndent()
    }
}

data class QueueView(val category: String, val status: String, val count: Long, val oldest: Instant)
data class OutcomeView(val minutes: Int, val category: String, val status: String, val count: Long)
