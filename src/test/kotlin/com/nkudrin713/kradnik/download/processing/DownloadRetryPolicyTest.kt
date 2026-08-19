package com.nkudrin713.kradnik.download.processing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadRetryPolicyTest {
    private val now = Instant.parse("2026-07-16T10:00:00Z")
    private val policy = DownloadRetryPolicy(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun appliesExponentialBackoff() {
        assertEquals(now.plusSeconds(15), policy.retryAt(attempt = 1))
        assertEquals(now.plusSeconds(30), policy.retryAt(attempt = 2))
        assertEquals(now.plusSeconds(60), policy.retryAt(attempt = 3))
    }

    @Test
    fun respectsLongerServerRetryAfter() {
        assertEquals(
            now.plusSeconds(90),
            policy.retryAt(attempt = 2, retryAfter = Duration.ofSeconds(90)),
        )
    }

    @Test
    fun capsBackoff() {
        assertEquals(now.plus(Duration.ofMinutes(10)), policy.retryAt(attempt = 20))
    }
}
