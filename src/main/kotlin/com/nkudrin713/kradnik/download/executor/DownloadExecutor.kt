package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import java.nio.file.Path
import java.time.Instant

interface DownloadExecutor {
    val strategies: Set<DownloadStrategy>

    suspend fun prepare(request: DownloadRequest): DownloadPreparation
}

interface PreparedDownloadSession {
    val metadata: YtDlpMetadataDto

    suspend fun download(
        request: DownloadRequest,
        outputDir: Path,
    ): DownloadedFile
}

sealed interface DownloadPreparation {
    data class Ready(
        val session: PreparedDownloadSession,
    ) : DownloadPreparation

    data class NotReady(
        val retryAt: Instant,
        val reason: String,
    ) : DownloadPreparation

    data class RetryableFailure(
        val retryAt: Instant,
        val reason: String,
    ) : DownloadPreparation

    data class SourceUnavailable(
        val reason: String,
    ) : DownloadPreparation

    data class TerminalFailure(
        val reason: String,
    ) : DownloadPreparation
}
