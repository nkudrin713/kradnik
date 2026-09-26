package com.nkudrin713.kradnik.download.domain

import java.nio.file.Path

/** Ready-to-send local media. Its shape determines delivery independently of the requested product. */
sealed interface MediaArtifact {
    data class Video(val file: Path) : MediaArtifact

    data class Audio(val file: Path, val metadata: AudioMetadata) : MediaArtifact

    data class Document(val file: Path) : MediaArtifact

    data class Photos(val files: List<Path>) : MediaArtifact {
        init {
            require(files.isNotEmpty()) { "At least one photo is required" }
        }
    }
}

data class AudioMetadata(
    val title: String?,
    val performer: String?,
    val durationSeconds: Int?,
)
