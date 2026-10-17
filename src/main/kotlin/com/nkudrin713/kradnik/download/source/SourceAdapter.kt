package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import java.nio.file.Path

interface SourceAdapter {
    val platform: DownloadPlatform
    fun supports(url: String): Boolean
    fun resolve(url: String): PlatformDownloadSpecs
    fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec
    suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource
    suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile
}

interface PreparedSource {
    val metadata: MediaMetadata
    val canonicalUrl: String? get() = null
}

/** User-selected output parameters; source adapters translate these into their saved execution settings. */
sealed interface MediaSelection {
    data class Video(val videoId: String, val audioId: String?, val key: String) : MediaSelection
    data class Audio(val quality: String) : MediaSelection
    data object Cover : MediaSelection
}

interface PostSourceAdapter : SourceAdapter {
    suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile
}

interface PlaylistSourceAdapter : SourceAdapter {
    suspend fun playlist(url: String, audioQuality: String): DownloadSpec?
    fun playlistItemUrl(entry: PlaylistAudioEntry): String
    fun playlistItemCacheKey(entry: PlaylistAudioEntry, source: SourceRequest?): String
    suspend fun downloadPlaylistItem(entry: PlaylistAudioEntry, directory: Path, source: SourceRequest?): DownloadedFile
}

interface PreparedPostSource : PreparedSource {
    val post: SourcePost
    val mediaKinds: List<PostMediaKind> get() = post.items.map { it.kind }
}

/** Ordered source positions are distinct even when several positions reference the same media. */
data class SourcePostItem(val kind: PostMediaKind, val reference: String)

/** Extracted content only; choice planners own the supported menu actions. */
data class SourcePost(
    val items: List<SourcePostItem>,
    val text: String? = null,
    val authorUsername: String? = null,
) {
    init {
        require(items.size <= 20 && (items.isNotEmpty() || !text.isNullOrBlank())) { "A post requires text or up to 20 media items" }
    }
}
