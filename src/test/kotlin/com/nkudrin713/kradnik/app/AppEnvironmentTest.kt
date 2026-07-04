package com.nkudrin713.kradnik.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppEnvironmentTest {
    @Test
    fun `parses prod environment`() {
        AppEnvironment.fromConfig("prod") shouldBe AppEnvironment.PROD
    }

    @Test
    fun `parses test environment`() {
        AppEnvironment.fromConfig("test") shouldBe AppEnvironment.TEST
    }

    @Test
    fun `rejects unsupported environment`() {
        assertThrows<IllegalArgumentException> {
            AppEnvironment.fromConfig("local")
        }
    }
}
