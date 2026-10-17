package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Preserves saved encoding parameters. The caller owns the directory, including partial output on failure. */
@Component
class PlaylistEntryDownloader(
    private val engine: DownloadEngine,
    private val limits: TelegramUploadLimits = TelegramUploadLimits(TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES),
) {
    fun cacheKey(entry: PlaylistAudioEntry, source: SourceRequest? = null): String = engine.playlistSource(entry, source).playlistItemCacheKey(entry, source)

    suspend fun download(entry: PlaylistAudioEntry, destination: Path, source: SourceRequest? = null): DownloadedFile = coroutineScope {
        checkWorkspace(destination)
        val monitor = launch {
            while (true) {
                delay(250)
                checkWorkspace(destination)
            }
        }
        try {
            engine.playlistSource(entry, source).downloadPlaylistItem(entry, destination, source).also {
                checkWorkspace(destination)
                if (Files.size(it.file) > limits.maxUploadBytes) throw DownloadRejectedException("Playlist item exceeds size limit")
            }
        } finally {
            monitor.cancelAndJoin()
        }
    }

    private suspend fun checkWorkspace(directory: Path) = withContext(Dispatchers.IO) {
        if (Files.getFileStore(directory).usableSpace < 64L * 1024 * 1024) {
            throw PlaylistOperationException(TelegramMessage.PLAYLIST_DISK_FULL)
        }
        // Includes partial source files and temporary tag/thumbnail copies. Every individual file
        // is bounded even when yt-dlp cannot determine Content-Length before downloading.
        Files.walk(directory).use { files ->
            var total = 0L
            files.filter { Files.isRegularFile(it) }.forEach { file ->
                val size = try {
                    Files.size(file)
                } catch (_: NoSuchFileException) {
                    0L
                }
                total += size
                if (size > limits.maxUploadBytes || total > limits.maxUploadBytes * 3) {
                    throw DownloadRejectedException("Playlist item exceeds size limit")
                }
            }
        }
    }
}
