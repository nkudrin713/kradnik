package com.nkudrin713.kradnik.download.processing

fun interface JobProgress {
    fun update(phase: DownloadPhase)
}

enum class DownloadPhase { DOWNLOADING, PACKING, UPLOADING }
