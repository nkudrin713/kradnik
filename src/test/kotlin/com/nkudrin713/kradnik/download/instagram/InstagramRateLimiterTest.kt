package com.nkudrin713.kradnik.download.instagram

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InstagramRateLimiterTest {
    private val now = Instant.parse("2026-07-15T10:00:00Z")
    private val clock = MutableClock(now)
    private val limiter = InstagramRateLimiter(
        clock = clock,
        minInterval = Duration.ofSeconds(30),
        initialCooldown = Duration.ofMinutes(30),
        maxCooldown = Duration.ofHours(6),
        cooldownMultiplier = 2,
    )

    @Test
    fun grantsFirstRequestAndDefersNextRequest() {
        val granted = assertIs<InstagramRateLimitDecision.Granted>(limiter.acquire())
        val deferred = assertIs<InstagramRateLimitDecision.Deferred>(limiter.acquire())

        assertEquals(now, granted.acquiredAt)
        assertEquals(now.plusSeconds(30), deferred.retryAt)
    }

    @Test
    fun grantsOnlyOneConcurrentRequest() {
        val executor = Executors.newFixedThreadPool(2)

        val decisions = try {
            executor.invokeAll(
                listOf(
                    Callable { limiter.acquire() },
                    Callable { limiter.acquire() },
                )
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, decisions.count { it is InstagramRateLimitDecision.Granted })
        assertEquals(1, decisions.count { it is InstagramRateLimitDecision.Deferred })
    }

    @Test
    fun increasesCooldownAfterConsecutiveThrottles() {
        assertEquals(now.plus(Duration.ofMinutes(30)), limiter.recordThrottle(now, null))
        assertEquals(now.plus(Duration.ofHours(1)), limiter.recordThrottle(now, null))
    }

    @Test
    fun respectsLongerRetryAfter() {
        val retryAt = limiter.recordThrottle(now, Duration.ofHours(2))

        assertEquals(now.plus(Duration.ofHours(2)), retryAt)
    }

    @Test
    fun successClearsOlderCooldown() {
        limiter.recordThrottle(now, null)
        limiter.recordSuccess(now.plusSeconds(1))

        assertIs<InstagramRateLimitDecision.Granted>(limiter.acquire())
    }

    @Test
    fun staleSuccessDoesNotClearNewerThrottle() {
        limiter.recordThrottle(now, null)
        limiter.recordSuccess(now.minusSeconds(1))

        val deferred = assertIs<InstagramRateLimitDecision.Deferred>(limiter.acquire())

        assertEquals(now.plus(Duration.ofMinutes(30)), deferred.retryAt)
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
