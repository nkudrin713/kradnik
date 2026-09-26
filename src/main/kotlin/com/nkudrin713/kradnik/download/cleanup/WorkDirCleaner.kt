package com.nkudrin713.kradnik.download.cleanup

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Component
class WorkDirCleaner(
    @Value($$"${download.work-dir:/tmp/kradnik-downloads}") workDir: String,
) {
    private val root = Path.of(workDir)
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(root.isAbsolute && root.normalize().parent != null) {
            "download.work-dir must be an absolute, dedicated directory below the filesystem root"
        }
    }

    /**
     * Creates a fresh job directory beneath the configured workspace root, creating the root if necessary.
     * Fails if the job directory already exists so a new attempt cannot reuse an interrupted download.
     */
    fun create(jobId: Long): Path {
        Files.createDirectories(root)
        // A failed startup cleanup must never let yt-dlp reuse a partial download.
        return Files.createDirectory(root.resolve(jobId.toString()))
    }

    /**
     * Removes entries with numeric job names before any worker starts; other root entries are left untouched.
     * Directory traversal does not follow symlinks. Per-entry deletion failures are logged by [deleteRecursively].
     */
    fun cleanInterruptedJobs() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().toLongOrNull() != null }.forEach(::deleteRecursively)
        }
    }

    /**
     * Deletes children before their parent without following directory symlinks; an absent path is a no-op.
     * Logs deletion failures instead of failing the job. Callers must supply a disposable workspace path:
     * this method does not verify that [path] is beneath the configured root.
     */
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
