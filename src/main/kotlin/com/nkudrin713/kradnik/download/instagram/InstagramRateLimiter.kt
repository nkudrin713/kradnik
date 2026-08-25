package com.nkudrin713.kradnik.download.instagram

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A process-local limiter with a minimum request interval and adaptive cooldown after throttling.
 * Its state is not shared between application instances.
 */
@Component
class InstagramRateLimiter(
    private val clock: Clock,
    @Value("\${download.instagram.rate-limit.min-interval:30s}")
    private val minInterval: Duration,
    @Value("\${download.instagram.rate-limit.initial-cooldown:30m}")
    private val initialCooldown: Duration,
    @Value("\${download.instagram.rate-limit.max-cooldown:6h}")
    private val maxCooldown: Duration,
    @Value("\${download.instagram.rate-limit.cooldown-multiplier:2}")
    private val cooldownMultiplier: Int,
) {
    private val lock = Any()
    private var nextAllowedAt = Instant.EPOCH
    private var cooldownUntil: Instant? = null
    private var consecutiveThrottles = 0
    private var lastThrottleAt: Instant? = null

    init {
        require(!minInterval.isNegative && !minInterval.isZero) { "Instagram rate limit interval must be positive" }
        require(!initialCooldown.isNegative && !initialCooldown.isZero) {
            "Instagram initial cooldown must be positive"
        }
        require(maxCooldown >= initialCooldown) {
            "Instagram max cooldown must not be shorter than initial cooldown"
        }
        require(cooldownMultiplier >= 1) { "Instagram cooldown multiplier must be at least one" }
    }

    /** Reserves the next request slot or reports when another attempt may start. */
    fun acquire(): InstagramRateLimitDecision = synchronized(lock) {
        val now = clock.instant()
        val allowedAt = maxOf(nextAllowedAt, cooldownUntil ?: Instant.EPOCH)
        if (now.isBefore(allowedAt)) {
            return@synchronized InstagramRateLimitDecision.Deferred(allowedAt)
        }

        nextAllowedAt = now.plus(minInterval)
        InstagramRateLimitDecision.Granted(now)
    }

    /** Clears cooldown only when no newer request has already observed throttling. */
    fun recordSuccess(acquiredAt: Instant) = synchronized(lock) {
        if (lastThrottleAt?.isBefore(acquiredAt) == false) {
            return@synchronized
        }

        cooldownUntil = null
        consecutiveThrottles = 0
    }

    /** Extends the cooldown using both local backoff and the remote Retry-After value. */
    fun recordThrottle(
        acquiredAt: Instant,
        retryAfter: Duration?,
    ): Instant = synchronized(lock) {
        val now = clock.instant()
        consecutiveThrottles += 1
        val retryAfterCooldown = retryAfter?.takeIf { !it.isNegative } ?: Duration.ZERO
        val cooldown = maxOf(cooldown(consecutiveThrottles), retryAfterCooldown)
        cooldownUntil = maxOf(cooldownUntil ?: Instant.EPOCH, now.plus(cooldown))
        lastThrottleAt = maxOf(lastThrottleAt ?: Instant.EPOCH, acquiredAt, now)
        requireNotNull(cooldownUntil)
    }

    private fun cooldown(throttleCount: Int): Duration {
        var result = initialCooldown
        repeat(throttleCount - 1) {
            if (result >= maxCooldown) {
                return maxCooldown
            }
            result = result.multipliedBy(cooldownMultiplier.toLong())
        }
        return minOf(result, maxCooldown)
    }
}

sealed interface InstagramRateLimitDecision {
    data class Granted(
        val acquiredAt: Instant,
    ) : InstagramRateLimitDecision

    data class Deferred(
        val retryAt: Instant,
    ) : InstagramRateLimitDecision
}
