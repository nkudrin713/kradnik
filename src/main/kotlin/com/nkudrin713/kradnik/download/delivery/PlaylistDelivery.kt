package com.nkudrin713.kradnik.download.delivery

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import java.nio.file.Path

interface PlaylistDelivery {
    suspend fun checkStorage()
    suspend fun stagePlaylistAudio(file: DownloadedFile, entry: PlaylistAudioEntry): String
    suspend fun sendPlaylistAudios(context: DeliveryContext, playlistEntries: List<PlaylistAudioEntry>, results: List<PlaylistAudioResult>): String
    suspend fun sendArchive(context: DeliveryContext, path: Path): String
}
