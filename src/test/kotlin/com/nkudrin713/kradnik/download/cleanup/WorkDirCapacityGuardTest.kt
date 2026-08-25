package com.nkudrin713.kradnik.download.cleanup

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkDirCapacityGuardTest {
    @Test
    fun allowsDownloadWhenCapacityIncludesMergeAndReserve(@TempDir tempDir: Path) {
        val guard = guard(
            uploadLimit = 100,
            usableBytes = 250,
            reserveBytes = 50,
            workDir = tempDir,
        )

        guard.ensureDownloadCapacity(tempDir)
    }

    @Test
    fun rejectsTranscodeWhenCapacityIsInsufficient(@TempDir tempDir: Path) {
        val guard = guard(
            uploadLimit = 100,
            usableBytes = 149,
            reserveBytes = 50,
            workDir = tempDir,
        )

        val error = assertFailsWith<InsufficientWorkDirSpaceException> {
            guard.ensureTranscodeCapacity(tempDir)
        }

        assertEquals(150, error.requiredBytes)
        assertEquals(149, error.usableBytes)
    }

    @Test
    fun rejectsDefaultWorkDirInLocalMode() {
        assertFailsWith<IllegalArgumentException> {
            DefaultWorkDirCapacityGuard(
                uploadLimits = TelegramUploadLimits(maxUploadBytes = 100, localMode = true),
                workDirSpaceProvider = WorkDirSpaceProvider { Long.MAX_VALUE },
                reserveBytes = 0,
                configuredWorkDir = "/tmp/kradnik-downloads",
            )
        }
    }

    private fun guard(
        uploadLimit: Long,
        usableBytes: Long,
        reserveBytes: Long,
        workDir: Path,
    ): DefaultWorkDirCapacityGuard {
        return DefaultWorkDirCapacityGuard(
            uploadLimits = TelegramUploadLimits(maxUploadBytes = uploadLimit),
            workDirSpaceProvider = WorkDirSpaceProvider { usableBytes },
            reserveBytes = reserveBytes,
            configuredWorkDir = workDir.toString(),
        )
    }
}
