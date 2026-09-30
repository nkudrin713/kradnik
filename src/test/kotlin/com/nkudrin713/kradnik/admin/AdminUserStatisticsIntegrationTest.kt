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
