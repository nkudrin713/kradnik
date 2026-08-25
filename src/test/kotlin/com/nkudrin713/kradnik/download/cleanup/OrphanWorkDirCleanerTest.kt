package com.nkudrin713.kradnik.download.cleanup

import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class OrphanWorkDirCleanerTest {
    private val downloadJobService: DownloadJobService = mockk()

    @Test
    fun removesOrphanAttemptAndKeepsOwnedLease(@TempDir workDir: Path) {
        val activeLease = UUID.randomUUID()
        val orphanLease = UUID.randomUUID()
        val activeAttempt = attemptDirectory(workDir, jobId = 1, leaseToken = activeLease)
        val orphanAttempt = attemptDirectory(workDir, jobId = 2, leaseToken = orphanLease)
        every { downloadJobService.ownsLease(1, activeLease) } returns true
        every { downloadJobService.ownsLease(2, orphanLease) } returns false
        val cleaner = OrphanWorkDirCleaner(
            downloadJobService = downloadJobService,
            workDirCleaner = DefaultWorkDirCleaner(),
            workDir = workDir.toString(),
        )

        cleaner.clean()

        assertEquals(true, activeAttempt.exists())
        assertEquals(false, orphanAttempt.exists())
        assertEquals(false, workDir.resolve("2").exists())
    }

    @Test
    fun ignoresDirectoriesOutsideJobLeaseLayout(@TempDir workDir: Path) {
        val unrelated = workDir.resolve("manual").resolve("files").createDirectories()
        unrelated.resolve("keep.txt").writeText("keep")
        val cleaner = OrphanWorkDirCleaner(
            downloadJobService = downloadJobService,
            workDirCleaner = DefaultWorkDirCleaner(),
            workDir = workDir.toString(),
        )

        cleaner.clean()

        assertEquals(true, unrelated.exists())
    }

    private fun attemptDirectory(workDir: Path, jobId: Long, leaseToken: UUID): Path {
        val directory = workDir.resolve(jobId.toString()).resolve(leaseToken.toString()).createDirectories()
        directory.resolve("media.mp4").writeText("media")
        return directory
    }
}
