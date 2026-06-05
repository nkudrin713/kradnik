package com.nkudrin713.kradnik.download.domain

fun DownloadJob.requiredId(): Long {
    return requireNotNull(id)
}
