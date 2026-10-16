package com.nkudrin713.kradnik.download.single

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.PostMedia
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class PostHandler(
    private val source: DownloadEngine,
    private val preflight: DownloadPreflightService,
    private val videoPreparer: TelegramVideoPreparer,
) : SingleMediaHandler {
    override suspend fun produce(request: SingleMediaRequest, directory: Path, jobId: Long, progress: JobProgress): MediaArtifact {
        val prepared = source.preparePost(request.source)
        progress.update(DownloadPhase.DOWNLOADING)
        var totalBytes = 0L
        val items = prepared.post.items.mapIndexed { index, item ->
            val kind = item.kind
            // Each video conversion writes telegram-video.mp4; keep carousel positions isolated.
            val itemDirectory = Files.createDirectories(directory.resolve("item-${index + 1}"))
            val downloaded = source.downloadPostItem(request.source, prepared, index, itemDirectory)
            val file = when (kind) {
                PostMediaKind.PHOTO -> downloaded.also(preflight::validatePhoto)
                PostMediaKind.VIDEO -> videoPreparer.prepare(downloaded, itemDirectory, jobId)
            }
            totalBytes = Math.addExact(totalBytes, file.sizeBytes)
            preflight.validatePostSize(totalBytes)
            PostMedia(kind, file.file)
        }
        return MediaArtifact.Post(items, prepared.post.text)
    }
}
