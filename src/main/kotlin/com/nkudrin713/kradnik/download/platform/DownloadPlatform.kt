package com.nkudrin713.kradnik.download.platform

enum class DownloadPlatform(
    val dbValue: String,
    val displayName: String,
    val icon: String = "/admin/icons/service.svg",
) {
    YOUTUBE("youtube", "YouTube", "/admin/icons/youtube.svg"),
    INSTAGRAM("instagram", "Instagram", "/admin/icons/instagram.svg"),
    VK("vk", "VK", "/admin/icons/vk.svg"),
    ;

    companion object {
        fun fromDb(value: String): DownloadPlatform {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown download platform: $value")
        }
    }
}
