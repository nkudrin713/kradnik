package com.nkudrin713.kradnik.download.cleanup

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkDirCleanerTest {
    private val cleaner = DefaultWorkDirCleaner()

    @Test
    fun deletesDirectoryRecursively(@TempDir tempDir: Path) {
        val workDir = tempDir.resolve("work")
        val nestedDir = workDir.resolve("nested").createDirectories()
        nestedDir.resolve("file.txt").writeText("content")

        cleaner.deleteRecursively(workDir)

        assertEquals(false, workDir.exists())
    }

    @Test
    fun ignoresMissingDirectory(@TempDir tempDir: Path) {
        val missingDir = tempDir.resolve("missing")

        cleaner.deleteRecursively(missingDir)

        assertEquals(false, missingDir.exists())
    }
}
