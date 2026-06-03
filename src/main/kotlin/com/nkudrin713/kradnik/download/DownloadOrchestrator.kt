package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class DownloadOrchestrator(
    private val platformResolver: PlatformResolver,
    private val ytDlpService: YtDlpService,
) {

    suspend fun download(
        job: DownloadJob,
        outputDir: Path,
    ): DownloadedFile {
        val request = platformResolver.resolve(job.originalUrl)
            .buildRequest(job.originalUrl, job.outputType)

        return ytDlpService.download(request, outputDir)
    }
}
