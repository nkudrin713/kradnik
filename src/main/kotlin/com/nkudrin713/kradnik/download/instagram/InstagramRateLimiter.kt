package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.ratelimit.RateLimitBucketKey
import com.nkudrin713.kradnik.download.ratelimit.RateLimitCoordinator
import com.nkudrin713.kradnik.download.ratelimit.RateLimitDecision
import com.nkudrin713.kradnik.download.ratelimit.RateLimitPolicy
import com.nkudrin713.kradnik.download.ratelimit.RateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class InstagramRateLimiter(
    private val coordinator: RateLimitCoordinator,
    @Value("\${download.instagram.rate-limit.scope:vps-direct}")
    scope: String,
    @Value("\${download.instagram.rate-limit.min-interval:30s}")
    minInterval: Duration,
    @Value("\${download.instagram.rate-limit.max-jitter:15s}")
    maxJitter: Duration,
    @Value("\${download.instagram.rate-limit.initial-cooldown:30m}")
    initialCooldown: Duration,
    @Value("\${download.instagram.rate-limit.max-cooldown:6h}")
    maxCooldown: Duration,
    @Value("\${download.instagram.rate-limit.cooldown-multiplier:2}")
    cooldownMultiplier: Int,
) : RateLimiter {
    private val key = RateLimitBucketKey(
        provider = "instagram",
        operation = "embed",
        scope = scope,
    )
    private val policy = RateLimitPolicy(
        minInterval = minInterval,
        maxJitter = maxJitter,
        initialCooldown = initialCooldown,
        maxCooldown = maxCooldown,
        cooldownMultiplier = cooldownMultiplier,
    )
    override fun acquire(): RateLimitDecision = coordinator.acquire(key, policy)

    override fun recordSuccess() {
        coordinator.recordSuccess(key)
    }

    override fun recordThrottle(retryAfter: Duration?): Instant {
        return coordinator.recordThrottle(key, policy, retryAfter)
    }
}
