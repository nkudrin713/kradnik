package com.nkudrin713.kradnik.download.cleanup

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

interface WorkDirCleaner {
    fun deleteRecursively(path: Path)
}

@Component
class DefaultWorkDirCleaner : WorkDirCleaner {
    override fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) {
            return
        }

        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
