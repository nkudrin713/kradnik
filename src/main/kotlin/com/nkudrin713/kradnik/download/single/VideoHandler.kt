package com.nkudrin713.kradnik.download.single

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class VideoHandler(private val engine: DownloadEngine, private val preflight: DownloadPreflightService, private val videoPreparer: TelegramVideoPreparer) : SingleMediaHandler {
    override suspend fun produce(request: SingleMediaRequest, directory: Path, jobId: Long, progress: JobProgress): MediaArtifact {
        val prepared = engine.prepare(request.source)
        val allowed = preflight.requireAllowed(request, prepared.metadata)
        progress.update(DownloadPhase.DOWNLOADING)
        val downloaded = engine.download(allowed.source, SourceMediaType.VIDEO, prepared, directory)
        val video = videoPreparer.prepare(downloaded, directory, jobId)
        preflight.validateFile(video)
        return MediaArtifact.Video(video.file)
    }
}
