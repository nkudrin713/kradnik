package com.nkudrin713.kradnik.download.ratelimit

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

@Component
class RateLimitCoordinator(
    private val store: RateLimitBucketStore,
    private val clock: Clock,
) {
    fun acquire(
        key: RateLimitBucketKey,
        policy: RateLimitPolicy,
    ): RateLimitDecision {
        return store.update(key) { state ->
            val now = clock.instant()
            val allowedAt = maxOf(state.nextAllowedAt, state.cooldownUntil ?: Instant.EPOCH)
            if (now.isBefore(allowedAt)) {
                return@update RateLimitDecision.Deferred(allowedAt)
            }

            state.nextAllowedAt = now.plus(policy.minInterval).plus(randomJitter(policy.maxJitter))
            state.lastRequestAt = now
            state.updatedAt = now
            RateLimitDecision.Granted(RateLimitPermit(now))
        }
    }

    fun recordSuccess(
        key: RateLimitBucketKey,
        permit: RateLimitPermit,
    ) {
        store.update(key) { state ->
            val now = clock.instant()
            if (state.lastThrottleAt?.isBefore(permit.acquiredAt) == false) {
                return@update
            }
            state.cooldownUntil = null
            state.consecutiveThrottles = 0
            state.lastSuccessAt = now
            state.updatedAt = now
        }
    }

    fun recordThrottle(
        key: RateLimitBucketKey,
        policy: RateLimitPolicy,
        permit: RateLimitPermit,
        retryAfter: Duration?,
    ): Instant {
        return store.update(key) { state ->
            val now = clock.instant()
            state.consecutiveThrottles += 1
            val configuredCooldown = cooldown(policy, state.consecutiveThrottles)
            val retryAfterCooldown = retryAfter?.takeIf { !it.isNegative } ?: Duration.ZERO
            val cooldown = maxOf(configuredCooldown, retryAfterCooldown)
            val cooldownUntil = now.plus(cooldown)
            state.cooldownUntil = maxOf(state.cooldownUntil ?: Instant.EPOCH, cooldownUntil)
            state.lastThrottleAt = maxOf(state.lastThrottleAt ?: Instant.EPOCH, permit.acquiredAt, now)
            state.updatedAt = now
            requireNotNull(state.cooldownUntil)
        }
    }

    private fun cooldown(
        policy: RateLimitPolicy,
        consecutiveThrottles: Int,
    ): Duration {
        var result = policy.initialCooldown
        repeat(consecutiveThrottles - 1) {
            if (result >= policy.maxCooldown) {
                return policy.maxCooldown
            }
            result = result.multipliedBy(policy.cooldownMultiplier.toLong())
        }
        return minOf(result, policy.maxCooldown)
    }

    private fun randomJitter(maxJitter: Duration): Duration {
        val maxMillis = maxJitter.toMillis()
        if (maxMillis == 0L) {
            return Duration.ZERO
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(maxMillis + 1))
    }
}
