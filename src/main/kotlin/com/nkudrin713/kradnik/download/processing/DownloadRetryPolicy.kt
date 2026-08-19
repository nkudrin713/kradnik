package com.nkudrin713.kradnik.download.processing

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class DownloadRetryPolicy(
    private val clock: Clock,
) {
    fun retryAt(
        attempt: Int,
        retryAfter: Duration? = null,
    ): Instant {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        val backoff = minOf(
            BASE_DELAY.multipliedBy(1L shl exponent),
            MAX_DELAY,
        )
        val requestedDelay = retryAfter
            ?.takeUnless(Duration::isNegative)
            ?: Duration.ZERO

        return clock.instant().plus(maxOf(backoff, requestedDelay))
    }

    private companion object {
        private val BASE_DELAY = Duration.ofSeconds(15)
        private val MAX_DELAY = Duration.ofMinutes(10)
        private const val MAX_EXPONENT = 10
    }
}
