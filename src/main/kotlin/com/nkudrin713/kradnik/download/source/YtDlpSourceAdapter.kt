package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class YtDlpSourceAdapter(private val ytDlp: YtDlpService) : PostSourceAdapter {
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource {
        if (request.platform == DownloadPlatform.THREADS) {
            val post = ytDlp.extractPostMetadata(request, catalog)
            val entries = post.entries.orEmpty().ifEmpty { listOf(post) }
            require(entries.size in 1..20) { "A Threads post must contain 1 to 20 videos" }
            val metadata = (entries.singleOrNull() ?: post).toMediaMetadata().copy(
                contentType = if (entries.size > 1) MediaContentType.CAROUSEL else MediaContentType.VIDEO,
            )
            return YtDlpPreparedSource(metadata, post.webpageUrl, entries.size)
        }
        val metadata = if (catalog) ytDlp.extractCatalogMetadata(request) else ytDlp.extractMetadata(request)
        return YtDlpPreparedSource(metadata.toMediaMetadata())
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is YtDlpPreparedSource)
        if (request.platform == DownloadPlatform.THREADS) {
            require(type == SourceMediaType.VIDEO && prepared.mediaKinds.size == 1)
            return downloadPostItem(request, prepared, 0, directory)
        }
        return ytDlp.download(request, directory)
    }

    override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
        require(request.platform == DownloadPlatform.THREADS && prepared is YtDlpPreparedSource)
        require(index in prepared.mediaKinds.indices)
        return ytDlp.download(request.copy(extraArgs = request.extraArgs + listOf("--playlist-items", (index + 1).toString())), directory)
    }
}

data class YtDlpPreparedSource(override val metadata: MediaMetadata, override val canonicalUrl: String? = null, val videoCount: Int = 1) : PreparedPostSource {
    override val mediaKinds = List(videoCount) { PostMediaKind.VIDEO }
}
