package com.nkudrin713.kradnik.download.single

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.PostMedia
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.source.PreparedPostSource
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
        val prepared = source.prepare(request.source, catalog = false) as PreparedPostSource
        progress.update(DownloadPhase.DOWNLOADING)
        var totalBytes = 0L
        val items = prepared.mediaKinds.mapIndexed { index, kind ->
            // Each video conversion writes telegram-video.mp4; keep carousel positions isolated.
            val itemDirectory = Files.createDirectories(directory.resolve("item-${index + 1}"))
            val downloaded = source.downloadPostItem(request.source, prepared, index, itemDirectory)
            val file = when (kind) {
                PostMediaKind.PHOTO -> downloaded
                PostMediaKind.VIDEO -> videoPreparer.prepare(downloaded, itemDirectory, jobId)
            }
            totalBytes = Math.addExact(totalBytes, file.sizeBytes)
            // A rich post is one upload request, with a bounded total media size.
            preflight.validateFile(DownloadedFile(file.file, totalBytes))
            PostMedia(kind, file.file)
        }
        return MediaArtifact.Post(items)
    }
}
