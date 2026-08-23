package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadChoiceMetadataCacheTest {
    private val clock = MutableClock(Instant.parse("2026-08-23T10:00:00Z"))
    private val cache = DownloadChoiceMetadataCache(
        clock = clock,
        ttl = Duration.ofMinutes(30),
        maxEntries = 2,
    )

    @Test
    fun returnsCachedMetadataUntilTtlExpires() = runTest {
        var loads = 0

        val first = cache.getOrLoad("video") { metadata((++loads).toString()) }
        val cached = cache.getOrLoad("video") { metadata((++loads).toString()) }
        clock.advance(Duration.ofMinutes(31))
        val refreshed = cache.getOrLoad("video") { metadata((++loads).toString()) }

        assertEquals("1", first.id)
        assertEquals("1", cached.id)
        assertEquals("2", refreshed.id)
        assertEquals(2, loads)
    }

    @Test
    fun sharesOneLoadBetweenConcurrentRequests() = runTest {
        var loads = 0
        val release = CompletableDeferred<Unit>()
        val first = async {
            cache.getOrLoad("video") {
                loads++
                release.await()
                metadata("shared")
            }
        }
        runCurrent()
        val second = async {
            cache.getOrLoad("video") {
                loads++
                metadata("unexpected")
            }
        }
        runCurrent()

        assertEquals(1, loads)
        release.complete(Unit)
        assertEquals("shared", first.await().id)
        assertEquals("shared", second.await().id)
    }

    private fun metadata(id: String): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = id,
            title = "Title",
            extractor = "youtube",
            webpageUrl = "https://example.com/video",
            thumbnail = null,
            duration = BigDecimal.ONE,
            ext = null,
            width = null,
            height = null,
            fps = null,
            filesize = null,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = null,
            format = null,
            track = null,
            artist = null,
            creator = null,
            uploader = null,
            channel = null,
            requestedFormats = null,
        )
    }
}

private class MutableClock(
    private var current: Instant,
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
