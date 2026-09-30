package com.nkudrin713.kradnik.admin

import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate

/** Queue and job-outcome projections; the rest of the dashboard does not depend on JPA or the job schema. */
class AdminQueries(private val jdbc: JdbcTemplate) {

    fun queues(): List<QueueView> = jdbc.query(
        QUEUES_SQL,
    ) { rs, _ -> QueueView(rs.getString("workload_type"), rs.getString("status"), rs.getLong("jobs"), rs.getTimestamp("oldest").toInstant()) }

    fun outcomes(): List<OutcomeView> = jdbc.query(
        OUTCOMES_SQL,
    ) { rs, _ -> OutcomeView(rs.getInt("minutes"), rs.getString("workload_type"), rs.getString("status"), rs.getLong("jobs")) }

    fun allTimeOutcomes(): List<AllTimeOutcome> = jdbc.query(ALL_TIME_OUTCOMES_SQL) { rs, _ ->
        AllTimeOutcome(rs.getString("status"), rs.getLong("jobs"))
    }

    fun jobTrend(): List<JobDay> = jdbc.query(JOB_TREND_SQL) { rs, _ ->
        JobDay(rs.getObject("day", LocalDate::class.java), rs.getLong("completed"), rs.getLong("failed"), rs.getLong("cancelled"))
    }

    fun users(): UserSummary = requireNotNull(
        jdbc.queryForObject(USERS_SQL) { rs, _ ->
            UserSummary(
                rs.getLong("total"),
                rs.getLong("new_today"),
                rs.getLong("new_week"),
                rs.getLong("active_today"),
                rs.getLong("active_week"),
                rs.getLong("active_month"),
                rs.getLong("returning_today"),
                rs.getTimestamp("first_seen")?.toInstant(),
                rs.getTimestamp("last_seen")?.toInstant(),
            )
        },
    )

    fun userTrend(): List<UserDay> = jdbc.query(USER_TREND_SQL) { rs, _ ->
        UserDay(rs.getObject("day", LocalDate::class.java), rs.getLong("active"), rs.getLong("new_users"))
    }

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
        val ALL_TIME_OUTCOMES_SQL = """
        SELECT status, count(*) AS jobs
        FROM download_jobs
        WHERE status IN ('completed', 'failed', 'cancelled_by_user')
        GROUP BY status
        """.trimIndent()
        val JOB_TREND_SQL = """
        WITH days AS (
            SELECT generate_series(
                (now() AT TIME ZONE 'UTC')::date - 13,
                (now() AT TIME ZONE 'UTC')::date,
                interval '1 day'
            )::date AS day
        ), outcomes AS (
            SELECT (completed_at AT TIME ZONE 'UTC')::date AS day,
                   count(*) FILTER (WHERE status = 'completed') AS completed,
                   count(*) FILTER (WHERE status = 'failed') AS failed,
                   count(*) FILTER (WHERE status = 'cancelled_by_user') AS cancelled
            FROM download_jobs
            WHERE completed_at >= now() - interval '14 days'
              AND status IN ('completed', 'failed', 'cancelled_by_user')
            GROUP BY day
        )
        SELECT days.day, coalesce(outcomes.completed, 0) AS completed,
               coalesce(outcomes.failed, 0) AS failed,
               coalesce(outcomes.cancelled, 0) AS cancelled
        FROM days LEFT JOIN outcomes USING (day)
        ORDER BY days.day
        """.trimIndent()
        val USERS_SQL = """
        WITH users AS (
            SELECT min(first_seen_at) AS first_seen, max(last_seen_at) AS last_seen
            FROM user_activity_days
            GROUP BY telegram_user_id
        ), bounds AS (
            SELECT (date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') AS today
        )
        SELECT count(*) AS total,
               count(*) FILTER (WHERE first_seen >= today) AS new_today,
               count(*) FILTER (WHERE first_seen >= today - interval '6 days') AS new_week,
               count(*) FILTER (WHERE last_seen >= today) AS active_today,
               count(*) FILTER (WHERE last_seen >= today - interval '6 days') AS active_week,
               count(*) FILTER (WHERE last_seen >= today - interval '29 days') AS active_month,
               count(*) FILTER (WHERE first_seen < today AND last_seen >= today) AS returning_today,
               min(first_seen) AS first_seen,
               max(last_seen) AS last_seen
        FROM users CROSS JOIN bounds
        """.trimIndent()
        val USER_TREND_SQL = """
        WITH days AS (
            SELECT generate_series(
                (now() AT TIME ZONE 'UTC')::date - 13,
                (now() AT TIME ZONE 'UTC')::date,
                interval '1 day'
            )::date AS day
        ), active AS (
            SELECT day, count(*) AS users FROM user_activity_days
            WHERE day >= (now() AT TIME ZONE 'UTC')::date - 13
            GROUP BY day
        ), first_days AS (
            SELECT min(day) AS day FROM user_activity_days GROUP BY telegram_user_id
        ), newcomers AS (
            SELECT day, count(*) AS users FROM first_days GROUP BY day
        )
        SELECT days.day, coalesce(active.users, 0) AS active,
               coalesce(newcomers.users, 0) AS new_users
        FROM days
        LEFT JOIN active USING (day)
        LEFT JOIN newcomers USING (day)
        ORDER BY days.day
        """.trimIndent()
    }
}

data class QueueView(val category: String, val status: String, val count: Long, val oldest: Instant)
data class OutcomeView(val minutes: Int, val category: String, val status: String, val count: Long)
data class AllTimeOutcome(val status: String, val count: Long)
data class JobDay(val day: LocalDate, val completed: Long, val failed: Long, val cancelled: Long)
data class UserSummary(
    val total: Long,
    val newToday: Long,
    val newWeek: Long,
    val activeToday: Long,
    val activeWeek: Long,
    val activeMonth: Long,
    val returningToday: Long,
    val firstSeen: Instant?,
    val lastSeen: Instant?,
)
data class UserDay(val day: LocalDate, val active: Long, val newUsers: Long)
