package com.nkudrin713.kradnik.download.domain

import java.nio.file.Path

data class DownloadedFile(
    val file: Path,
    val sizeBytes: Long,
    val additionalFiles: List<Path> = emptyList(),
) {
    val files: List<Path>
        get() = listOf(file) + additionalFiles
}
