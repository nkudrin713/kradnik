package com.nkudrin713.kradnik.download.telegram

sealed interface CachedMedia {
    data class Single(val kind: TelegramMediaKind, val fileId: String) : CachedMedia
    data class Photos(val fileIds: List<String>) : CachedMedia
}

enum class TelegramMediaKind { VIDEO, AUDIO, DOCUMENT }
