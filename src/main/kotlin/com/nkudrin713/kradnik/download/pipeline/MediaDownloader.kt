package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import java.io.File

interface MediaDownloader {
    fun supports(metadata: MediaMetadata, outputType: DownloadOutputType): Boolean

    suspend fun download(url: String, metadata: MediaMetadata, outputType: DownloadOutputType, outputDir: File): DownloadedFile
}
