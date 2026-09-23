package com.nkudrin713.kradnik.download.cleanup

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Component
class WorkDirCleaner(
    @Value("\${download.work-dir:/tmp/kradnik-downloads}") workDir: String,
) {
    private val root = Path.of(workDir)
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(root.isAbsolute && root.normalize().parent != null) {
            "download.work-dir must be an absolute, dedicated directory below the filesystem root"
        }
    }

    fun create(jobId: Long): Path {
        Files.createDirectories(root)
        // A failed startup cleanup must never let yt-dlp reuse a partial download.
        return Files.createDirectory(root.resolve(jobId.toString()))
    }

    /** Startup only, before any worker starts. Unrelated files and symlinks are not followed. */
    fun cleanInterruptedJobs() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().toLongOrNull() != null }.forEach(::deleteRecursively)
        }
    }

    fun deleteRecursively(path: Path) {
        try {
            if (!Files.exists(path)) return
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        } catch (error: Exception) {
            logger.warn("Could not remove download workspace: {}", path, error)
        }
    }
}
