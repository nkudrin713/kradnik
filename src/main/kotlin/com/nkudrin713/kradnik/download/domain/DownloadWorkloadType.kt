package com.nkudrin713.kradnik.download.domain

enum class DownloadWorkloadType(val dbValue: String) {
    SINGLE("single"),
    PLAYLIST_AUDIO("playlist_audio"),
    ;

    companion object {
        fun fromDb(value: String): DownloadWorkloadType = entries.firstOrNull { it.dbValue == value }
            ?: throw IllegalArgumentException("Unknown download workload type: $value")
    }
}
