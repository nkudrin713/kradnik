package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.observability.BotTelemetry
import com.nkudrin713.kradnik.observability.RuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Shared bounded item execution. A null result denotes a handled item failure; cancellation is not a failure. */
@Component
class PlaylistItems(
    @Value($$"${download.playlist-item-parallelism:2}") private val parallelism: Int = 2,
    private val telemetry: BotTelemetry = BotTelemetry.NONE,
) {
    init {
        require(parallelism > 0) { "download.playlist-item-parallelism must be positive" }
    }

    suspend fun <T : Any> map(jobId: Long, entries: List<PlaylistAudioEntry>, action: suspend (PlaylistAudioEntry) -> T?): List<T> = coroutineScope {
        telemetry.playlist(jobId, entries.size)
        val semaphore = Semaphore(parallelism)
        entries.map { entry ->
            async {
                semaphore.withPermit {
                    telemetry.item(jobId, true)
                    try {
                        action(entry).also { if (it == null) telemetry.error(RuntimeError.PLAYLIST_ITEM) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        telemetry.error(RuntimeError.PLAYLIST_ITEM)
                        throw error
                    } finally {
                        telemetry.item(jobId, false)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
}
