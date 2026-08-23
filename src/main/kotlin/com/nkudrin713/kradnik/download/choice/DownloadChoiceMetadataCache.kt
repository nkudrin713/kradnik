package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import kotlinx.coroutines.CompletableDeferred
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

@Component
class DownloadChoiceMetadataCache(
    private val clock: Clock,
    @Value("\${download.choice-metadata-cache-ttl:30m}")
    private val ttl: Duration = Duration.ofMinutes(30),
    @Value("\${download.choice-metadata-cache-max-entries:200}")
    private val maxEntries: Int = 200,
) {
    private val entries = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<YtDlpMetadataDto>>()

    init {
        require(ttl.isPositive()) { "download.choice-metadata-cache-ttl must be positive" }
        require(maxEntries > 0) { "download.choice-metadata-cache-max-entries must be positive" }
    }

    suspend fun getOrLoad(
        cacheKey: String,
        loader: suspend () -> YtDlpMetadataDto,
    ): YtDlpMetadataDto {
        get(cacheKey)?.let { return it }

        val pending = CompletableDeferred<YtDlpMetadataDto>()
        val active = inFlight.putIfAbsent(cacheKey, pending)
        if (active != null) {
            return active.await()
        }

        return try {
            val metadata = get(cacheKey) ?: loader()
            put(cacheKey, metadata)
            pending.complete(metadata)
            metadata
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(cacheKey, pending)
        }
    }

    private fun get(cacheKey: String): YtDlpMetadataDto? = synchronized(entries) {
        val entry = entries[cacheKey] ?: return@synchronized null
        if (entry.expiresAt <= clock.instant()) {
            entries.remove(cacheKey)
            return@synchronized null
        }
        entry.metadata
    }

    private fun put(cacheKey: String, metadata: YtDlpMetadataDto) = synchronized(entries) {
        entries[cacheKey] = CacheEntry(
            metadata = metadata,
            expiresAt = clock.instant().plus(ttl),
        )
    }
}

private data class CacheEntry(
    val metadata: YtDlpMetadataDto,
    val expiresAt: Instant,
)
