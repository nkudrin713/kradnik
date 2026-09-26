package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.PostMediaKind

sealed interface CachedMedia {
    data class Single(val kind: TelegramMediaKind, val fileId: String) : CachedMedia
    data class Photos(val fileIds: List<String>) : CachedMedia
    data class Post(val items: List<CachedPostItem>) : CachedMedia {
        init {
            require(items.size in 1..20 && items.all { it.fileId.isNotBlank() }) { "Invalid cached post" }
        }
    }
}

data class CachedPostItem(val kind: PostMediaKind, val fileId: String)

enum class TelegramMediaKind { VIDEO, AUDIO, DOCUMENT }
