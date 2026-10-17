package com.nkudrin713.kradnik.download.platform

/** Checks admission without exposing how switches are stored or administered. */
fun interface SourceAdmission {
    fun requireEnabled(platform: DownloadPlatform)
}
