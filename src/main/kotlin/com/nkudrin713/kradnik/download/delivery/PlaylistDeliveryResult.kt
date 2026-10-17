package com.nkudrin713.kradnik.download.delivery

import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult

data class PlaylistDeliveryResult(val successfulCount: Int, val failedCount: Int, val receipt: String, val failures: List<PlaylistAudioResult> = emptyList())
