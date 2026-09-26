package com.nkudrin713.kradnik.admin

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

class AdminLoginThrottleTest {
    @Test
    fun loginBudgetIsBoundedAndRecoversWithoutTrackingRemoteAddresses() {
        val clock = AdminTestClock()
        val throttle = AdminLoginThrottle(clock)
        var passed = 0
        val chain = FilterChain { _, _ -> passed++ }
        repeat(10) {
            throttle.doFilter(request(), MockHttpServletResponse(), chain)
        }
        val rejected = MockHttpServletResponse()
        throttle.doFilter(request(), rejected, chain)
        assertEquals(10, passed)
        assertEquals(429, rejected.status)
        assertEquals("60", rejected.getHeader("Retry-After"))
        throttle.doFilter(MockHttpServletRequest("GET", "/admin/api/snapshot"), MockHttpServletResponse(), chain)
        assertEquals(11, passed)
        clock.advance(60)
        throttle.doFilter(request(), MockHttpServletResponse(), chain)
        assertEquals(12, passed)
    }

    private fun request() = MockHttpServletRequest("POST", "/login").apply { servletPath = "/login" }
}
