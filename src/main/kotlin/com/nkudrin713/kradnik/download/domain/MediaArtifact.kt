package com.nkudrin713.kradnik.download.domain

import java.nio.file.Path

/** Ready-to-send local media. Its shape determines delivery independently of the requested product. */
sealed interface MediaArtifact {
    data class Video(val file: Path) : MediaArtifact

    data class Audio(val file: Path, val metadata: AudioMetadata) : MediaArtifact

    data class Document(val file: Path) : MediaArtifact

    data class Post(val items: List<PostMedia>) : MediaArtifact {
        init {
            require(items.size in 1..20) { "A post must contain 1 to 20 media items" }
        }
    }

    data class Photos(val files: List<Path>) : MediaArtifact {
        init {
            require(files.isNotEmpty()) { "At least one photo is required" }
        }
    }
}

enum class PostMediaKind { PHOTO, VIDEO }

data class PostMedia(val kind: PostMediaKind, val file: Path)

data class AudioMetadata(
    val title: String?,
    val performer: String?,
    val durationSeconds: Int?,
)
