package com.nkudrin713.kradnik.download.ratelimit

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RateLimitCoordinatorTest {
    private val now = Instant.parse("2026-07-15T10:00:00Z")
    private val key = RateLimitBucketKey("instagram", "embed", "vps-direct")
    private val policy = RateLimitPolicy(
        minInterval = Duration.ofSeconds(30),
        maxJitter = Duration.ZERO,
        initialCooldown = Duration.ofMinutes(30),
        maxCooldown = Duration.ofHours(6),
        cooldownMultiplier = 2,
    )
    private val store = InMemoryRateLimitBucketStore()
    private val coordinator = RateLimitCoordinator(
        store = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun grantsFirstRequestAndDefersNextRequest() {
        assertEquals(RateLimitDecision.Granted, coordinator.acquire(key, policy))

        val deferred = assertIs<RateLimitDecision.Deferred>(coordinator.acquire(key, policy))

        assertEquals(now.plusSeconds(30), deferred.retryAt)
    }

    @Test
    fun increasesCooldownAfterConsecutiveThrottles() {
        assertEquals(now.plus(Duration.ofMinutes(30)), coordinator.recordThrottle(key, policy, null))
        assertEquals(now.plus(Duration.ofHours(1)), coordinator.recordThrottle(key, policy, null))
    }

    @Test
    fun respectsLongerRetryAfter() {
        val retryAt = coordinator.recordThrottle(key, policy, Duration.ofHours(2))

        assertEquals(now.plus(Duration.ofHours(2)), retryAt)
    }

    @Test
    fun successResetsThrottleState() {
        coordinator.recordThrottle(key, policy, null)

        coordinator.recordSuccess(key)

        val state = store.state(key)
        assertEquals(0, state.consecutiveThrottles)
        assertEquals(null, state.cooldownUntil)
        assertEquals(now, state.lastSuccessAt)
    }

    private class InMemoryRateLimitBucketStore : RateLimitBucketStore {
        private val states = mutableMapOf<RateLimitBucketKey, RateLimitBucketState>()

        override fun <T> update(
            key: RateLimitBucketKey,
            action: (RateLimitBucketState) -> T,
        ): T {
            return action(
                states.getOrPut(key) {
                    RateLimitBucketState(
                        key = key,
                        nextAllowedAt = Instant.EPOCH,
                        cooldownUntil = null,
                        consecutiveThrottles = 0,
                        lastRequestAt = null,
                        lastSuccessAt = null,
                        lastThrottleAt = null,
                        updatedAt = Instant.EPOCH,
                    )
                }
            )
        }

        fun state(key: RateLimitBucketKey): RateLimitBucketState = requireNotNull(states[key])
    }
}
