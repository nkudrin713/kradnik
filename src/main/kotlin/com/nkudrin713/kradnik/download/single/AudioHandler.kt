package com.nkudrin713.kradnik.download.single

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.AudioMetadata
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.source.SourceMediaType
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class AudioHandler(private val engine: DownloadEngine, private val preflight: DownloadPreflightService) : SingleMediaHandler {
    override suspend fun produce(request: SingleMediaRequest, directory: Path, jobId: Long, progress: JobProgress): MediaArtifact {
        val prepared = engine.prepare(request.source)
        val allowed = preflight.requireAllowed(request, prepared.metadata)
        progress.update(DownloadPhase.DOWNLOADING)
        val audio = engine.download(allowed.source, SourceMediaType.AUDIO, prepared, directory)
        preflight.validateFile(audio)
        val metadata = prepared.metadata
        return MediaArtifact.Audio(
            audio.file,
            AudioMetadata(
                metadata.track ?: metadata.title ?: "Audio",
                metadata.artist ?: metadata.uploader ?: metadata.channel ?: "Unknown",
                metadata.duration?.toInt(),
            ),
        )
    }
}
