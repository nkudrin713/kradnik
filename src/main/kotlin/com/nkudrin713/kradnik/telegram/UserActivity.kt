package com.nkudrin713.kradnik.telegram

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Records one aggregate row per user and UTC day; no Telegram content is stored. */
@Service
class UserActivity(private val jdbc: JdbcTemplate) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var failed = false

    fun record(userId: Long) {
        try {
            jdbc.update(
                """
                INSERT INTO user_activity_days (day, telegram_user_id, first_seen_at, last_seen_at)
                VALUES ((now() AT TIME ZONE 'UTC')::date, ?, now(), now())
                ON CONFLICT (day, telegram_user_id) DO UPDATE
                SET first_seen_at = LEAST(user_activity_days.first_seen_at, EXCLUDED.first_seen_at),
                    last_seen_at = GREATEST(user_activity_days.last_seen_at, EXCLUDED.last_seen_at)
                """.trimIndent(),
                userId,
            )
            failed = false
        } catch (error: Exception) {
            if (!failed) logger.warn("User activity recording unavailable", error)
            failed = true
        }
    }
}
