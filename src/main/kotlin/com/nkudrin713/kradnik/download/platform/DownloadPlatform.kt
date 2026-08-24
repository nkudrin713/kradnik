package com.nkudrin713.kradnik.download.platform

enum class DownloadPlatform(
    val dbValue: String,
    val displayName: String,
) {
    YOUTUBE("youtube", "YouTube"),
    INSTAGRAM("instagram", "Instagram"),
    VK("vk", "VK");

    companion object {
        fun fromDb(value: String): DownloadPlatform {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown download platform: $value")
        }
    }
}
