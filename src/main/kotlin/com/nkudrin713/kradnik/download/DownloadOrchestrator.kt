package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class DownloadOrchestrator(
    private val ytDlpService: YtDlpService,
) {

    suspend fun download(
        request: DownloadRequest,
        outputDir: Path,
    ): DownloadedFile {
        return ytDlpService.download(request, outputDir)
    }
}
