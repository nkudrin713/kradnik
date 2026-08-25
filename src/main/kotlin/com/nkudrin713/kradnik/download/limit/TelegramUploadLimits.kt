package com.nkudrin713.kradnik.download.limit

data class TelegramUploadLimits(
    val maxUploadBytes: Long,
    val localMode: Boolean = false,
) {
    init {
        require(maxUploadBytes in 1..LOCAL_MAX_UPLOAD_BYTES) {
            "maxUploadBytes must be between 1 and $LOCAL_MAX_UPLOAD_BYTES"
        }
    }

    companion object {
        const val CLOUD_MAX_UPLOAD_BYTES = 45L * 1024L * 1024L
        const val LOCAL_MAX_UPLOAD_BYTES = 2_000_000_000L
    }
}
