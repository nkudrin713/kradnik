package com.nkudrin713.kradnik.download.platform

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class PlatformAvailabilityIntegrationTest {
    @Test
    fun switchesPersistAcrossRestartsAndNewRegistryEntriesNeedNoRows() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        val jdbc = JdbcTemplate(source)
        val availability = PlatformAvailability(jdbc).apply { load() }
        assertTrue(availability.list().all { it.enabled })

        availability.setEnabled(DownloadPlatform.INSTAGRAM, false)
        availability.setEnabled(DownloadPlatform.YOUTUBE, false)
        val restarted = PlatformAvailability(jdbc).apply { load() }
        assertFailsWith<PlatformDisabledException> { restarted.requireEnabled(DownloadPlatform.INSTAGRAM) }
        assertFailsWith<PlatformDisabledException> { restarted.requireEnabled(DownloadPlatform.YOUTUBE) }
        restarted.requireEnabled(DownloadPlatform.VK)
        restarted.setEnabled(DownloadPlatform.INSTAGRAM, true)
        restarted.setEnabled(DownloadPlatform.INSTAGRAM, true)
        assertTrue(PlatformAvailability(jdbc).apply { load() }.list().first { it.id == "instagram" }.enabled)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform_availability", Int::class.java))
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
