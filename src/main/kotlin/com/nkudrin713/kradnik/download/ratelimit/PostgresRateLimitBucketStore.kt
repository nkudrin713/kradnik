package com.nkudrin713.kradnik.download.ratelimit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class PostgresRateLimitBucketStore(
    private val jdbcTemplate: JdbcTemplate,
) : RateLimitBucketStore {
    @Transactional
    override fun <T> update(
        key: RateLimitBucketKey,
        action: (RateLimitBucketState) -> T,
    ): T {
        jdbcTemplate.update(
            """
                INSERT INTO request_rate_limit_buckets (provider, operation, scope)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
            """.trimIndent(),
            key.provider,
            key.operation,
            key.scope,
        )

        val state = requireNotNull(
            jdbcTemplate.query(
                """
                    SELECT *
                    FROM request_rate_limit_buckets
                    WHERE provider = ? AND operation = ? AND scope = ?
                    FOR UPDATE
                """.trimIndent(),
                { resultSet, _ -> resultSet.toState() },
                key.provider,
                key.operation,
                key.scope,
            ).singleOrNull()
        ) { "Rate limit bucket was not created: $key" }
        val result = action(state)
        jdbcTemplate.update(
            """
                UPDATE request_rate_limit_buckets
                SET next_allowed_at = ?,
                    cooldown_until = ?,
                    consecutive_throttles = ?,
                    last_request_at = ?,
                    last_success_at = ?,
                    last_throttle_at = ?,
                    updated_at = ?
                WHERE provider = ? AND operation = ? AND scope = ?
            """.trimIndent(),
            Timestamp.from(state.nextAllowedAt),
            state.cooldownUntil?.let(Timestamp::from),
            state.consecutiveThrottles,
            state.lastRequestAt?.let(Timestamp::from),
            state.lastSuccessAt?.let(Timestamp::from),
            state.lastThrottleAt?.let(Timestamp::from),
            Timestamp.from(state.updatedAt),
            state.key.provider,
            state.key.operation,
            state.key.scope,
        )
        return result
    }

    private fun ResultSet.toState(): RateLimitBucketState {
        return RateLimitBucketState(
            key = RateLimitBucketKey(
                provider = getString("provider"),
                operation = getString("operation"),
                scope = getString("scope"),
            ),
            nextAllowedAt = getTimestamp("next_allowed_at").toInstant(),
            cooldownUntil = getTimestamp("cooldown_until")?.toInstant(),
            consecutiveThrottles = getInt("consecutive_throttles"),
            lastRequestAt = getTimestamp("last_request_at")?.toInstant(),
            lastSuccessAt = getTimestamp("last_success_at")?.toInstant(),
            lastThrottleAt = getTimestamp("last_throttle_at")?.toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
    }
}
