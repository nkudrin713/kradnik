package com.nkudrin713.kradnik.ytdlp.youtube

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.pipeline.MediaDownloader
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import java.io.File
import org.springframework.stereotype.Service

@Service
class YouTubeAudioDownloader(
    private val ytDlpService: YtDlpService,
) : MediaDownloader {
    override fun supports(metadata: MediaMetadata, outputType: DownloadOutputType): Boolean =
        metadata.extractor == "youtube" && outputType == DownloadOutputType.AUDIO

    override suspend fun download(
        url: String,
        metadata: MediaMetadata,
        outputType: DownloadOutputType,
        outputDir: File,
    ): DownloadedFile =
        ytDlpService.downloadAudio(url, outputDir)
}
