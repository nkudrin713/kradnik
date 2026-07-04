package com.nkudrin713.kradnik.analytics

enum class AnalyticsEventType(val dbValue: String) {
    DOWNLOAD_REQUESTED("download_requested"),
    TELEGRAM_CACHE_HIT("telegram_cache_hit"),
    TELEGRAM_CACHE_MISS("telegram_cache_miss"),
    METADATA_EXTRACTED("metadata_extracted"),
    PREFLIGHT_ALLOWED("preflight_allowed"),
    PREFLIGHT_REJECTED("preflight_rejected"),
    DOWNLOAD_STARTED("download_started"),
    UPLOAD_STARTED("upload_started"),
    DOWNLOAD_COMPLETED("download_completed"),
    DOWNLOAD_FAILED("download_failed"),
    DOWNLOAD_REJECTED("download_rejected"),
}
