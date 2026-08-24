package com.nkudrin713.kradnik.download.ratelimit

import java.time.Duration
import java.time.Instant

sealed interface RateLimitDecision {
    data class Granted(
        val permit: RateLimitPermit,
    ) : RateLimitDecision

    data class Deferred(
        val retryAt: Instant,
    ) : RateLimitDecision
}

data class RateLimitPermit(
    val acquiredAt: Instant,
)

data class RateLimitBucketKey(
    val provider: String,
    val operation: String,
    val scope: String,
) {
    init {
        require(provider.isNotBlank()) { "Rate limit provider must not be blank" }
        require(operation.isNotBlank()) { "Rate limit operation must not be blank" }
        require(scope.isNotBlank()) { "Rate limit scope must not be blank" }
    }
}

data class RateLimitPolicy(
    val minInterval: Duration,
    val maxJitter: Duration,
    val initialCooldown: Duration,
    val maxCooldown: Duration,
    val cooldownMultiplier: Int,
) {
    init {
        require(!minInterval.isNegative && !minInterval.isZero) { "Rate limit interval must be positive" }
        require(!maxJitter.isNegative) { "Rate limit jitter must not be negative" }
        require(!initialCooldown.isNegative && !initialCooldown.isZero) {
            "Rate limit initial cooldown must be positive"
        }
        require(maxCooldown >= initialCooldown) { "Rate limit max cooldown must not be shorter than initial cooldown" }
        require(cooldownMultiplier >= 1) { "Rate limit cooldown multiplier must be at least one" }
    }
}

interface RateLimitBucketStore {
    fun <T> update(
        key: RateLimitBucketKey,
        action: (RateLimitBucketState) -> T,
    ): T
}

data class RateLimitBucketState(
    val key: RateLimitBucketKey,
    var nextAllowedAt: Instant,
    var cooldownUntil: Instant?,
    var consecutiveThrottles: Int,
    var lastRequestAt: Instant?,
    var lastSuccessAt: Instant?,
    var lastThrottleAt: Instant?,
    var updatedAt: Instant,
)
