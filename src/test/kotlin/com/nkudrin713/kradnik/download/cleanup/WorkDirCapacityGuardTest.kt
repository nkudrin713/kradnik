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
            cloudMaxWorkspaceBytes = 200,
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
    fun rejectsCloudDownloadWhenCapacityDoesNotIncludeWorkspaceLimitAndReserve(@TempDir tempDir: Path) {
        val guard = guard(
            uploadLimit = 100,
            usableBytes = 549,
            reserveBytes = 50,
            workDir = tempDir,
            cloudMaxWorkspaceBytes = 500,
        )

        val error = assertFailsWith<InsufficientWorkDirSpaceException> {
            guard.ensureDownloadCapacity(tempDir)
        }

        assertEquals(550, error.requiredBytes)
        assertEquals(549, error.usableBytes)
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
        cloudMaxWorkspaceBytes: Long = uploadLimit * 2,
    ): DefaultWorkDirCapacityGuard {
        return DefaultWorkDirCapacityGuard(
            uploadLimits = TelegramUploadLimits(maxUploadBytes = uploadLimit),
            workDirSpaceProvider = WorkDirSpaceProvider { usableBytes },
            reserveBytes = reserveBytes,
            configuredWorkDir = workDir.toString(),
            cloudMaxWorkspaceBytes = cloudMaxWorkspaceBytes,
        )
    }
}
