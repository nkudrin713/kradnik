package com.nkudrin713.kradnik.ytdlp

import java.io.File

data class DownloadedFile(
    val file: File,
    val ext: String,
    val sizeBytes: Long,
    val args: List<String>,
)
