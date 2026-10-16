package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import java.nio.file.Path

interface SourceAdapter {
    suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource
    suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile
}

sealed interface PreparedSource {
    val metadata: MediaMetadata
    val canonicalUrl: String? get() = null
}

interface PostSourceAdapter : SourceAdapter {
    suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile
}

interface PreparedPostSource : PreparedSource {
    val post: SourcePost
    val mediaKinds: List<PostMediaKind> get() = post.items.map { it.kind }
}

/** Ordered source positions are distinct even when several positions reference the same media. */
data class SourcePostItem(val kind: PostMediaKind, val reference: String)

data class SourcePost(
    val items: List<SourcePostItem>,
    val text: String? = null,
    val mode: PostMode = PostMode.FULL,
    val singleVideoOptions: Boolean = true,
    val authorUsername: String? = null,
) {
    init {
        require(items.size <= 20 && (items.isNotEmpty() || !text.isNullOrBlank())) { "A post requires text or up to 20 media items" }
    }
}

enum class PostMode { FULL, VIDEOS_ONLY }
