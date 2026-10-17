package com.nkudrin713.kradnik.download.threads

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.domain.UnsupportedUrlException
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PostSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourcePost
import com.nkudrin713.kradnik.download.source.SourcePostItem
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpSelections
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import com.nkudrin713.kradnik.ytdlp.toMediaMetadata
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class ThreadsSourceAdapter(
    private val ytDlp: YtDlpService,
    @Value($$"${download.yt-dlp.plugin-directory:yt-dlp-plugins}") private val pluginDirectory: String = "yt-dlp-plugins",
) : PostSourceAdapter {
    override val platform = DownloadPlatform.THREADS
    override fun supports(url: String): Boolean = isThreadsHost(url)
    override fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec = YtDlpSelections.select(spec, selection)

    override fun resolve(url: String): PlatformDownloadSpecs {
        val media = parseThreadsMediaUrl(url) ?: throw UnsupportedUrlException("Threads URL is not supported")
        val video = DownloadSpec(
            originalUrl = media.original,
            normalizedUrl = media.normalized,
            cacheKey = ResultKeyFactory.source("threads:${if (media.shared) "share" else "post"}:${media.id}", OutputType.VIDEO, "threads_video"),
            outputType = OutputType.VIDEO,
            platform = DownloadPlatform.THREADS,
            presetName = "threads_video",
            formatSelector = "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a]/b[height<=1280][ext=mp4]/b[height<=1280]/best",
            extraArgs = YtDlpPresets.MERGE_MP4_ARGS,
        )
        return PlatformDownloadSpecs(video, video)
    }

    override suspend fun prepare(request: SourceRequest, catalog: Boolean): ThreadsPreparedSource {
        val metadata = ytDlp.extractPostMetadata(request, catalog, sourceOptions())
        val canonical = metadata.webpageUrl?.let(::parseThreadsMediaUrl)?.takeUnless { it.shared }
            ?: throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Threads post identity is missing")
        val entries = metadata.entries.orEmpty().ifEmpty { listOf(metadata) }
        val post = SourcePost(
            entries.mapIndexed { index, item -> SourcePostItem(PostMediaKind.VIDEO, item.id ?: index.toString()) },
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
        return ytDlp.download(request.copy(extraArgs = request.extraArgs + listOf("--playlist-items", (index + 1).toString())), directory, sourceOptions())
    }

    private fun sourceOptions(): List<String> = listOf("--plugin-dirs", Path.of(pluginDirectory).toAbsolutePath().toString(), "--concat-playlist", "never")
}

data class ThreadsPreparedSource(override val metadata: MediaMetadata, override val canonicalUrl: String, override val post: SourcePost) : PreparedPostSource
