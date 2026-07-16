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
        val granted = assertIs<RateLimitDecision.Granted>(coordinator.acquire(key, policy))

        val deferred = assertIs<RateLimitDecision.Deferred>(coordinator.acquire(key, policy))

        assertEquals(now, granted.permit.acquiredAt)
        assertEquals(now.plusSeconds(30), deferred.retryAt)
    }

    @Test
    fun increasesCooldownAfterConsecutiveThrottles() {
        val permit = RateLimitPermit(now)

        assertEquals(now.plus(Duration.ofMinutes(30)), coordinator.recordThrottle(key, policy, permit, null))
        assertEquals(now.plus(Duration.ofHours(1)), coordinator.recordThrottle(key, policy, permit, null))
    }

    @Test
    fun respectsLongerRetryAfter() {
        val retryAt = coordinator.recordThrottle(key, policy, RateLimitPermit(now), Duration.ofHours(2))

        assertEquals(now.plus(Duration.ofHours(2)), retryAt)
    }

    @Test
    fun successResetsThrottleState() {
        coordinator.recordThrottle(key, policy, RateLimitPermit(now.minusSeconds(1)), null)
        val successfulPermit = RateLimitPermit(now.plusSeconds(1))

        coordinator.recordSuccess(key, successfulPermit)

        val state = store.state(key)
        assertEquals(0, state.consecutiveThrottles)
        assertEquals(null, state.cooldownUntil)
        assertEquals(now, state.lastSuccessAt)
    }

    @Test
    fun staleSuccessDoesNotClearNewerThrottle() {
        val stalePermit = RateLimitPermit(now.minusSeconds(1))
        val currentPermit = RateLimitPermit(now)
        coordinator.recordThrottle(key, policy, currentPermit, null)

        coordinator.recordSuccess(key, stalePermit)

        val state = store.state(key)
        assertEquals(1, state.consecutiveThrottles)
        assertEquals(now.plus(Duration.ofMinutes(30)), state.cooldownUntil)
        assertEquals(null, state.lastSuccessAt)
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
