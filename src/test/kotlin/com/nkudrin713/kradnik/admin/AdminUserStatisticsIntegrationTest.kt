package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.telegram.UserActivity
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class AdminUserStatisticsIntegrationTest {
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        jdbc = JdbcTemplate(source)
        jdbc.execute("TRUNCATE user_activity_days, download_jobs")
    }

    @Test
    fun dailyActivityDeduplicatesUsersAndProjectsCurrentWindows() {
        val today = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'UTC')::date", LocalDate::class.java)!!
        val activity = UserActivity(jdbc)
        insertDay(today.minusDays(1), 10)
        insertDay(today.minusDays(6), 12)
        insertDay(today.minusDays(8), 13)
        insertDay(today.minusDays(29), 14)
        insertDay(today.minusDays(31), 15)
        activity.record(10)
        activity.record(10)
        activity.record(11)

        val users = AdminQueries(jdbc).users()
        assertEquals(6, users.total)
        assertEquals(1, users.newToday)
        assertEquals(3, users.newWeek)
        assertEquals(2, users.activeToday)
        assertEquals(3, users.activeWeek)
        assertEquals(5, users.activeMonth)
        assertEquals(1, users.returningToday)
        assertNotNull(users.firstSeen)
        assertNotNull(users.lastSeen)
        assertTrue(users.firstSeen < users.lastSeen)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_activity_days WHERE day = ? AND telegram_user_id = 10", Int::class.java, Date.valueOf(today)))

        val trend = AdminQueries(jdbc).userTrend()
        assertEquals(14, trend.size)
        assertEquals(UserDay(today, 2, 1), trend.last())
        assertEquals(UserDay(today.minusDays(1), 1, 1), trend[trend.lastIndex - 1])
    }

    @Test
    fun allTimeUsersCountUniqueUsersWithinEachMonthAndFillGaps() {
        val month = jdbc.queryForObject("SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date", LocalDate::class.java)!!
        insertDay(month.minusMonths(2), 10)
        insertDay(month.minusMonths(2).plusDays(1), 10)
        insertDay(month, 10)
        insertDay(month, 11)

        assertEquals(
            listOf(UserDay(month.minusMonths(2), 1, 1), UserDay(month.minusMonths(1), 0, 0), UserDay(month, 2, 1)),
            AdminQueries(jdbc).allTimeUserTrend(),
        )
    }

    @Test
    fun allTimeJobsGroupUtcMonthsAndPreserveOutcomeCategories() {
        val month = jdbc.queryForObject("SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date", LocalDate::class.java)!!
        insertJob("completed", month.minusMonths(2))
        insertJob("completed", month)
        insertJob("failed", month)
        insertJob("cancelled_by_user", month)
        insertJob("processing", month)
        jdbc.update("UPDATE download_jobs SET completed_at = NULL WHERE status = 'processing'")

        assertEquals(
            listOf(JobDay(month.minusMonths(2), 1, 0, 0), JobDay(month.minusMonths(1), 0, 0, 0), JobDay(month, 1, 1, 1)),
            AdminQueries(jdbc).allTimeJobTrend(),
        )
    }

    @Test
    fun allTimeTrendsHandleEmptyHistory() {
        val month = jdbc.queryForObject("SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date", LocalDate::class.java)!!
        val queries = AdminQueries(jdbc)
        assertEquals(listOf(UserDay(month, 0, 0)), queries.allTimeUserTrend())
        assertEquals(listOf(JobDay(month, 0, 0, 0)), queries.allTimeJobTrend())
    }

    private fun insertJob(status: String, day: LocalDate) {
        jdbc.update(
            "INSERT INTO download_jobs (telegram_user_id, telegram_chat_id, original_url, normalized_url, cache_key, download_preset, selected_format, platform, status, completed_at) VALUES (1, 1, 'https://example.test', 'https://example.test', 'test', '', '', 'youtube', ?, ?)",
            status,
            Timestamp.from(day.atStartOfDay().toInstant(ZoneOffset.UTC)),
        )
    }

    private fun insertDay(day: LocalDate, userId: Long) {
        val at = Timestamp.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC))
        jdbc.update(
            "INSERT INTO user_activity_days (day, telegram_user_id, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)",
            Date.valueOf(day),
            userId,
            at,
            at,
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
