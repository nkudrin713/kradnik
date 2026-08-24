package com.nkudrin713.kradnik.download.executor

enum class DownloadStrategy(val dbValue: String) {
    YT_DLP("yt_dlp"),
    YOUTUBE_YT_DLP("youtube_yt_dlp"),
    VK_YT_DLP("vk_yt_dlp"),
    INSTAGRAM_EMBED("instagram_embed"),
    COVER_YT_DLP("cover_yt_dlp"),
    COVER_INSTAGRAM_EMBED("cover_instagram_embed");

    fun coverStrategy(): DownloadStrategy {
        return when (this) {
            INSTAGRAM_EMBED, COVER_INSTAGRAM_EMBED -> COVER_INSTAGRAM_EMBED
            YT_DLP, YOUTUBE_YT_DLP, VK_YT_DLP, COVER_YT_DLP -> COVER_YT_DLP
        }
    }

    companion object {
        fun fromDb(value: String): DownloadStrategy {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown download strategy: $value")
        }
    }
}
