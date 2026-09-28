package com.nkudrin713.kradnik.admin

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminBackupTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun createsPrivateDumpAndRejectsConcurrentRun() {
        val executable = script(
            $$"""
            #!/bin/sh
            test "$PGPASSWORD" = secret || exit 1
            printf '%s\n' "$@" | grep -qx -- '--dbname=actual_database' || exit 1
            sleep 0.2
            for arg in "$@"; do
              case "$arg" in --file=*) printf 'backup' > "${arg#--file=}" ;; esac
            done
            """.trimIndent(),
        )
        val backup = AdminBackup(environment(), jdbc(), executable.toString())
        try {
            assertEquals(1024L, backup.estimate().databaseBytes)
            assertTrue(backup.estimate().freeBytes > 0)
            val first = backup.start()
            assertEquals("running", first.state)
            assertEquals(first, backup.start())
            val result = await(backup)
            assertEquals("completed", result.state)
            assertEquals(6L, result.sizeBytes)
            assertEquals("backup", Files.readString(temp.resolve("backups").resolve(result.fileName)))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("backups").resolve(result.fileName))))
            assertFalse(Files.list(temp.resolve("backups")).use { it.anyMatch { file -> file.fileName.toString().endsWith(".partial") } })
        } finally {
            backup.close()
        }
    }

    @Test
    fun failureRemovesPartialDump() {
        val executable = script("#!/bin/sh\nexit 9")
        val backup = AdminBackup(environment(), jdbc(), executable.toString())
        try {
            backup.start()
            assertEquals("failed", await(backup).state)
            assertTrue(Files.list(temp.resolve("backups")).use { it.findAny().isEmpty })
        } finally {
            backup.close()
        }
    }

    private fun environment(): MockEnvironment = MockEnvironment()
        .withProperty("admin.backup-dir", temp.resolve("backups").toString())
        .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/actual_database")
        .withProperty("spring.datasource.username", "kradnik")
        .withProperty("spring.datasource.password", "secret")

    private fun jdbc(): JdbcTemplate = mockk<JdbcTemplate>().also {
        every { it.queryForObject("SELECT pg_database_size(current_database())", Long::class.java) } returns 1024L
    }

    private fun script(contents: String): Path = temp.resolve("fake-pg-dump.sh").also {
        Files.writeString(it, "$contents\n")
        it.toFile().setExecutable(true)
    }

    private fun await(backup: AdminBackup): BackupStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (backup.status().state == "running" && System.nanoTime() < deadline) Thread.sleep(10)
        return backup.status()
    }
}
