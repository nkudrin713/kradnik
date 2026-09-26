package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason

class PlaylistSizeLimitException(cause: Throwable? = null) : DownloadFailure(DownloadFailureReason.TOO_LARGE, "Playlist archive exceeds the size or workspace limit", cause)
