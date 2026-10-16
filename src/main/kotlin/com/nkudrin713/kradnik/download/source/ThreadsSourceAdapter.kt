package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.platform.parseThreadsMediaUrl
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class ThreadsSourceAdapter(private val ytDlp: YtDlpService) : PostSourceAdapter {
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): ThreadsPreparedSource {
        val metadata = ytDlp.extractPostMetadata(request, catalog)
        val canonical = metadata.webpageUrl?.let(::parseThreadsMediaUrl)?.takeUnless { it.shared }
            ?: throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Threads post identity is missing")
        val entries = metadata.entries.orEmpty().ifEmpty { listOf(metadata) }
        val post = SourcePost(
            entries.mapIndexed { index, item -> SourcePostItem(PostMediaKind.VIDEO, item.id ?: index.toString()) },
            mode = PostMode.VIDEOS_ONLY,
        )
        return ThreadsPreparedSource(
            (entries.singleOrNull() ?: metadata).toMediaMetadata().copy(contentType = if (entries.size > 1) MediaContentType.CAROUSEL else MediaContentType.VIDEO),
            canonical.normalized,
            post,
        )
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is ThreadsPreparedSource && type == SourceMediaType.VIDEO && prepared.post.items.size == 1)
        return downloadPostItem(request, prepared, 0, directory)
    }

    override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
        require(prepared is ThreadsPreparedSource && index in prepared.post.items.indices)
        return ytDlp.download(request.copy(extraArgs = request.extraArgs + listOf("--playlist-items", (index + 1).toString())), directory)
    }
}

data class ThreadsPreparedSource(override val metadata: MediaMetadata, override val canonicalUrl: String, override val post: SourcePost) : PreparedPostSource
