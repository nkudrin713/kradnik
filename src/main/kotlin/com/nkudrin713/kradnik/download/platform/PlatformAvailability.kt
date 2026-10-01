package com.nkudrin713.kradnik.download.platform

import jakarta.annotation.PostConstruct
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Persistent admission switches. Accepted jobs keep running; reads never query the database. */
@Service
@DependsOnDatabaseInitialization
class PlatformAvailability(private val jdbc: JdbcTemplate) {
    @Volatile
    private var states: Map<String, Boolean> = emptyMap()

    @PostConstruct
    fun load() {
        states = jdbc.query("SELECT platform, enabled FROM platform_availability") { row, _ ->
            row.getString("platform") to row.getBoolean("enabled")
        }.toMap()
    }

    fun list(): List<PlatformStatus> {
        val snapshot = states
        return DownloadPlatform.entries.map { platform ->
            PlatformStatus(platform.dbValue, platform.displayName, platform.icon, snapshot[platform.dbValue] ?: true)
        }
    }

    fun requireEnabled(platform: DownloadPlatform) {
        if (states[platform.dbValue] == false) throw PlatformDisabledException(platform)
    }

    @Synchronized
    fun setEnabled(platform: DownloadPlatform, enabled: Boolean): PlatformStatus {
        jdbc.update(
            """
            INSERT INTO platform_availability (platform, enabled) VALUES (?, ?)
            ON CONFLICT (platform) DO UPDATE SET enabled = EXCLUDED.enabled
            """.trimIndent(),
            platform.dbValue,
            enabled,
        )
        states = states + (platform.dbValue to enabled)
        return PlatformStatus(platform.dbValue, platform.displayName, platform.icon, enabled)
    }
}

data class PlatformStatus(val id: String, val name: String, val icon: String, val enabled: Boolean)

class PlatformDisabledException(val platform: DownloadPlatform) : RuntimeException("${platform.displayName} is disabled")
