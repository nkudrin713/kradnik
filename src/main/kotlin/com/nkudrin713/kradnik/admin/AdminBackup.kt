package com.nkudrin713.kradnik.admin

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs one PostgreSQL logical backup at a time, outside HTTP and collector threads. */
class AdminBackup(private val environment: Environment, private val jdbc: JdbcTemplate, private val executable: String = "pg_dump") : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "admin-backup").apply { isDaemon = true } }
    private val nameTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    @Volatile
    private var current = BackupStatus("idle")

    @Volatile
    private var process: Process? = null

    fun status(): BackupStatus = current

    fun databaseSize(): Long = requireNotNull(
        jdbc.queryForObject("SELECT pg_database_size(current_database())", Long::class.java),
    )

    fun estimate(): BackupEstimate {
        val directory = Path.of(environment.getRequiredProperty("admin.backup-dir"))
        Files.createDirectories(directory)
        return BackupEstimate(databaseSize(), Files.getFileStore(directory).usableSpace)
    }

    @Synchronized
    fun start(): BackupStatus {
        if (current.state == "running") return current
        val startedAt = Instant.now()
        current = BackupStatus("running", startedAt = startedAt)
        executor.execute { run(startedAt) }
        return current
    }

    private fun run(startedAt: Instant) {
        var partial: Path? = null
        var errorFile: Path? = null
        var result = BackupStatus("failed", startedAt, Instant.now())
        try {
            val directory = Path.of(environment.getRequiredProperty("admin.backup-dir"))
            Files.createDirectories(directory)
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val fileName = "kradnik-${nameTime.format(startedAt)}-${UUID.randomUUID().toString().take(8)}.dump"
            partial = Files.createTempFile(directory, ".kradnik-", ".partial")
            Files.setPosixFilePermissions(partial, PosixFilePermissions.fromString("rw-------"))
            errorFile = Files.createTempFile(directory, ".kradnik-", ".error")
            Files.setPosixFilePermissions(errorFile, PosixFilePermissions.fromString("rw-------"))
            val url = URI.create(environment.getRequiredProperty("spring.datasource.url").removePrefix("jdbc:"))
            require(url.scheme == "postgresql" && url.host != null && !url.path.isNullOrBlank() && url.path != "/" && url.rawQuery == null) {
                "Admin backup requires a single-host PostgreSQL JDBC URL without query parameters"
            }
            val command = listOf(
                executable, "--format=custom", "--no-password", "--lock-wait-timeout=5s",
                "--host=${url.host}",
                "--port=${url.port.takeIf { it > 0 } ?: 5432}",
                "--username=${environment.getRequiredProperty("spring.datasource.username")}",
                "--dbname=${url.path.removePrefix("/")}",
                "--file=$partial",
            )
            val builder = ProcessBuilder(command).redirectError(errorFile.toFile())
            builder.environment()["PGPASSWORD"] = environment.getRequiredProperty("spring.datasource.password")
            process = builder.start()
            if (!process!!.waitFor(15, TimeUnit.MINUTES)) {
                process!!.destroyForcibly()
                throw IllegalStateException("pg_dump exceeded 15 minutes")
            }
            if (process!!.exitValue() != 0) {
                logger.warn("Admin pg_dump failed: {}", Files.newBufferedReader(errorFile).use { it.readLine() }?.take(500))
                throw IllegalStateException("pg_dump exited with code ${process!!.exitValue()}")
            }
            check(Files.size(partial) > 0) { "pg_dump produced an empty file" }
            val destination = directory.resolve(fileName)
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
            partial = null
            result = BackupStatus("completed", startedAt, Instant.now(), fileName, Files.size(destination))
        } catch (error: Exception) {
            logger.warn("Admin database backup failed", error)
            result = BackupStatus("failed", startedAt, Instant.now())
        } finally {
            process = null
            listOfNotNull(partial, errorFile).forEach { file ->
                try {
                    Files.deleteIfExists(file)
                } catch (error: Exception) {
                    logger.warn("Admin backup temporary file could not be removed: {}", file, error)
                }
            }
            current = result
        }
    }

    override fun close() {
        process?.destroyForcibly()
        executor.shutdownNow()
    }
}

data class BackupStatus(
    val state: String,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
)

data class BackupEstimate(val databaseBytes: Long, val freeBytes: Long)

data class DatabaseSize(val bytes: Long)
