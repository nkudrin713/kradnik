package com.nkudrin713.kradnik.download.cleanup

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the shared work directory before
 * [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor] downloads or
 * [TelegramVideoPreparer][com.nkudrin713.kradnik.download.video.TelegramVideoPreparer] transcodes a file.
 */
interface WorkDirCapacityGuard {
    fun ensureDownloadCapacity(workDir: Path)

    fun ensureTranscodeCapacity(workDir: Path)
}

/**
 * Implements [WorkDirCapacityGuard] using the usable space of the actual file store.
 * Download checks reserve room for source and final files; transcode checks reserve one upload-sized output plus
 * the configured safety margin.
 */
@Component
class DefaultWorkDirCapacityGuard(
    private val uploadLimits: TelegramUploadLimits,
    private val workDirSpaceProvider: WorkDirSpaceProvider,
    @Value("\${download.work-dir-reserve-bytes:536870912}")
    private val reserveBytes: Long,
    @Value("\${download.work-dir:/tmp/kradnik-downloads}")
    private val configuredWorkDir: String,
) : WorkDirCapacityGuard {
    init {
        require(reserveBytes >= 0) { "download.work-dir-reserve-bytes must not be negative" }
        require(Path.of(configuredWorkDir).isAbsolute) { "download.work-dir must be an absolute path" }
        require(!uploadLimits.localMode || Path.of(configuredWorkDir).normalize() != Path.of(DEFAULT_WORK_DIR)) {
            "download.work-dir must point to the shared local Bot API volume when local mode is enabled"
        }
    }

    override fun ensureDownloadCapacity(workDir: Path) {
        ensureCapacity(
            workDir = workDir,
            requiredBytes = uploadLimits.maxUploadBytes * DOWNLOAD_CAPACITY_MULTIPLIER + reserveBytes,
        )
    }

    override fun ensureTranscodeCapacity(workDir: Path) {
        ensureCapacity(
            workDir = workDir,
            requiredBytes = uploadLimits.maxUploadBytes + reserveBytes,
        )
    }

    private fun ensureCapacity(workDir: Path, requiredBytes: Long) {
        val usableBytes = workDirSpaceProvider.usableBytes(workDir)
        if (usableBytes < requiredBytes) {
            throw InsufficientWorkDirSpaceException(
                requiredBytes = requiredBytes,
                usableBytes = usableBytes,
            )
        }
    }

    private companion object {
        private const val DOWNLOAD_CAPACITY_MULTIPLIER = 2L
        private const val DEFAULT_WORK_DIR = "/tmp/kradnik-downloads"
    }
}

fun interface WorkDirSpaceProvider {
    fun usableBytes(workDir: Path): Long
}

@Component
class FileStoreWorkDirSpaceProvider : WorkDirSpaceProvider {
    override fun usableBytes(workDir: Path): Long = Files.getFileStore(workDir).usableSpace
}

class InsufficientWorkDirSpaceException(
    val requiredBytes: Long,
    val usableBytes: Long,
) : RuntimeException(
    "Insufficient download work directory space: requiredBytes=$requiredBytes, usableBytes=$usableBytes"
)
