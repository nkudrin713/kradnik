package com.nkudrin713.kradnik.download.single

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.cover.CoverDownloader
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class CoverHandler(private val engine: DownloadEngine, private val preflight: DownloadPreflightService, private val covers: CoverDownloader) : SingleMediaHandler {
    override suspend fun produce(request: SingleMediaRequest, directory: Path, jobId: Long, progress: JobProgress): MediaArtifact {
        val prepared = engine.prepare(request.source, catalog = true)
        preflight.requireAllowed(request, prepared.metadata)
        progress.update(DownloadPhase.DOWNLOADING)
        val thumbnail = prepared.metadata.thumbnail
        require(!thumbnail.isNullOrBlank()) { "Cover is unavailable" }
        val cover = covers.download(thumbnail, directory)
        preflight.validateFile(cover)
        return MediaArtifact.Document(cover.file)
    }
}
