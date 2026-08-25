package com.nkudrin713.kradnik.download.cleanup

import com.nkudrin713.kradnik.download.service.DownloadJobService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Component
class OrphanWorkDirCleaner(
    private val downloadJobService: DownloadJobService,
    private val workDirCleaner: WorkDirCleaner,
    @Value("\${download.work-dir:/tmp/kradnik-downloads}")
    workDir: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workDir = Path.of(workDir)

    @Scheduled(
        fixedDelayString = "\${download.work-dir-cleanup-delay-ms:600000}",
        initialDelayString = "\${download.work-dir-cleanup-initial-delay-ms:60000}",
    )
    fun clean() {
        if (!Files.isDirectory(workDir)) {
            return
        }

        val jobDirectories = Files.list(workDir).use { paths ->
            paths
                .filter(Files::isDirectory)
                .toList()
        }
        jobDirectories.forEach(::cleanJobDirectorySafely)
    }

    private fun cleanJobDirectorySafely(jobDirectory: Path) {
        runCatching {
            cleanJobDirectory(jobDirectory)
        }.onFailure { error ->
            logger.warn("Failed to clean orphan download directory: path={}", jobDirectory, error)
        }
    }

    private fun cleanJobDirectory(jobDirectory: Path) {
        val jobId = jobDirectory.fileName.toString().toLongOrNull() ?: return
        val attemptDirectories = Files.list(jobDirectory).use { paths ->
            paths
                .filter(Files::isDirectory)
                .toList()
        }
        attemptDirectories.forEach { attemptDirectory -> cleanAttemptDirectory(jobId, attemptDirectory) }

        Files.list(jobDirectory).use { remaining ->
            if (!remaining.findAny().isPresent) {
                Files.deleteIfExists(jobDirectory)
            }
        }
    }

    private fun cleanAttemptDirectory(jobId: Long, attemptDirectory: Path) {
        val leaseToken = runCatching {
            UUID.fromString(attemptDirectory.fileName.toString())
        }.getOrNull() ?: return
        if (!downloadJobService.ownsLease(jobId, leaseToken)) {
            workDirCleaner.deleteRecursively(attemptDirectory)
        }
    }
}
