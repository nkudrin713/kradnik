package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

@Component
class PlaylistWorkspaceBudget(private val uploadLimits: TelegramUploadLimits) {
    suspend fun check(root: Path) = withContext(Dispatchers.IO) {
        // MP3s, the archive and temporary conversion files share one bounded workspace.
        val maxBytes = uploadLimits.maxUploadBytes * 3
        var usedBytes = 0L
        val context = currentCoroutineContext()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (attrs.isRegularFile) usedBytes += attrs.size()
                    if (usedBytes > maxBytes) throw PlaylistSizeLimitException()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    // Download processes can rename or remove temporary files during the scan.
                    if (error is NoSuchFileException) return FileVisitResult.CONTINUE
                    throw error
                }
            },
        )
        check(Files.getFileStore(root).usableSpace >= 64L * 1024 * 1024) { "Not enough disk space for playlist archive" }
    }
}
