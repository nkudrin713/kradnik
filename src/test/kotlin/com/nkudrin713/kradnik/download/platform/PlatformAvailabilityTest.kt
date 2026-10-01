package com.nkudrin713.kradnik.download.platform

import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformAvailabilityTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val availability = PlatformAvailability(jdbc)

    @Test
    fun registrySuppliesEveryServiceEnabledByDefault() {
        assertEquals(DownloadPlatform.entries.map { it.dbValue }, availability.list().map { it.id })
        assertTrue(availability.list().all { it.enabled && it.icon.startsWith("/admin/icons/") })
        DownloadPlatform.entries.forEach(availability::requireEnabled)
    }

    @Test
    fun failedWriteKeepsPreviousRuntimeState() {
        every { jdbc.update(any<String>(), DownloadPlatform.VK.dbValue, false) } returns 1
        availability.setEnabled(DownloadPlatform.VK, false)
        every { jdbc.update(any<String>(), DownloadPlatform.VK.dbValue, true) } throws DataAccessResourceFailureException("unavailable")

        assertFailsWith<DataAccessResourceFailureException> { availability.setEnabled(DownloadPlatform.VK, true) }
        assertFailsWith<PlatformDisabledException> { availability.requireEnabled(DownloadPlatform.VK) }
        availability.requireEnabled(DownloadPlatform.INSTAGRAM)
    }
}
