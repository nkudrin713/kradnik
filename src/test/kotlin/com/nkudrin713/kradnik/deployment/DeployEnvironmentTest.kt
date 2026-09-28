package com.nkudrin713.kradnik.deployment

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeployEnvironmentTest {
    private val hash = $$"$2b$12$" + "a".repeat(53)

    @Test
    fun disabledAdminRequiresNoCredentials() {
        val (status, output) = render()
        assertEquals(0, status)
        assertTrue(output.contains("ADMIN_ENABLED=false\n"))
        assertTrue(output.contains("ADMIN_PASSWORD_HASH=''\n"))
    }

    @Test
    fun enabledAdminPreservesLiteralBcryptAndHttpsSettings() {
        val (status, output) = render(credentials() + mapOf("ADMIN_COOKIE_SECURE" to "true", "ADMIN_PUBLIC_PORT" to "18080", "BOT_VERSION" to "v1.2.3"))
        assertEquals(0, status)
        assertTrue(output.contains("ADMIN_ENABLED=true\n"))
        assertTrue(output.contains("ADMIN_USERNAME=admin\n"))
        assertTrue(output.contains("ADMIN_PASSWORD_HASH='$hash'\n"))
        assertTrue(output.contains("ADMIN_COOKIE_SECURE=true\n"))
        assertTrue(output.contains("ADMIN_PUBLIC_PORT=18080\n"))
        assertTrue(output.contains("BOT_VERSION=v1.2.3\n"))
    }

    @Test
    fun enabledAdminRejectsMissingAndInvalidCredentialsBeforePrintingSecrets() {
        val invalid = listOf(
            mapOf("ADMIN_ENABLED" to "true"),
            credentials() + ("ADMIN_USERNAME" to "admin\nINJECTED=value"),
            credentials() + ("ADMIN_PASSWORD_HASH" to "plaintext-secret"),
            credentials() + ("ADMIN_PASSWORD_HASH" to hash.replace("12", "04")),
        )
        invalid.forEach { values ->
            val (status, output) = render(values)
            assertEquals(1, status)
            assertFalse(output.contains(hash))
            assertFalse(output.contains("plaintext-secret"))
            assertFalse(output.contains("POSTGRES_PASSWORD="))
            assertFalse(output.contains("INJECTED="))
        }
    }

    @Test
    fun invalidFlagsAndPortsFailClosed() {
        listOf("ADMIN_ENABLED" to "yes", "ADMIN_COOKIE_SECURE" to "yes", "ADMIN_PUBLIC_PORT" to "0", "ADMIN_PUBLIC_PORT" to "65536", "ADMIN_PUBLIC_PORT" to "8080\nINJECTED=true").forEach {
            assertEquals(1, render(mapOf(it)).first)
        }
    }

    private fun credentials() = mapOf("ADMIN_ENABLED" to "true", "ADMIN_USERNAME" to "admin", "ADMIN_PASSWORD_HASH" to hash)

    private fun render(values: Map<String, String> = emptyMap()): Pair<Int, String> {
        val builder = ProcessBuilder("/bin/bash", "scripts/render-deploy-env.sh", "kradnik:test").redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(mapOf("PATH" to "/usr/bin:/bin", "POSTGRES_PASSWORD" to "test-password", "TELEGRAM_BOT_TOKEN" to "test-token", "TELEGRAM_BOT_API_IMAGE" to "test:api", "YOUTUBE_PO_TOKEN_PROVIDER_IMAGE" to "test:provider"))
            putAll(values)
        }
        val process = builder.start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Environment renderer timed out")
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            process.destroyForcibly()
        }
    }
}
