package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaMetadataMapperTest {
    private val mapper = MediaMetadataMapper()

    @Test
    fun mapsMetadataWithAudioFields() {
        val actual = mapper.toMediaMetadata(
            metadata(
                title = "source title",
                track = "track",
                artist = "artist",
                duration = BigDecimal.valueOf(120),
            )
        )

        assertEquals("source title", actual.title)
        assertEquals("youtube", actual.extractor)
        assertEquals(120, actual.durationSeconds)
        assertEquals("track", actual.audioTitle)
        assertEquals("artist", actual.audioPerformer)
        assertEquals(1080, actual.width)
        assertEquals(1920, actual.height)
        assertEquals("https://example.com/video", actual.webpageUrl)
    }

    @Test
    fun fallsBackAudioTitleAndPerformer() {
        val actual = mapper.toMediaMetadata(
            metadata(
                title = null,
                track = null,
                artist = null,
                uploader = null,
                channel = null,
                extractor = null,
            )
        )

        assertEquals("Audio", actual.audioTitle)
        assertEquals("Unknown", actual.audioPerformer)
    }

    @Test
    fun usesOrderedAudioFallbacks() {
        val actual = mapper.toMediaMetadata(
            metadata(
                track = null,
                artist = null,
                uploader = "uploader",
                channel = "channel",
                extractor = "extractor",
            )
        )

        assertEquals("title", actual.audioTitle)
        assertEquals("uploader", actual.audioPerformer)
    }

    private fun metadata(
        title: String? = "title",
        extractor: String? = "youtube",
        duration: BigDecimal? = BigDecimal.valueOf(120),
        track: String? = "track",
        artist: String? = "artist",
        uploader: String? = "uploader",
        channel: String? = "channel",
    ): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = "id",
            title = title,
            extractor = extractor,
            webpageUrl = "https://example.com/video",
            thumbnail = null,
            duration = duration,
            ext = "mp4",
            width = 1080,
            height = 1920,
            fps = null,
            filesize = 100,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = "format",
            format = null,
            track = track,
            artist = artist,
            creator = null,
            uploader = uploader,
            channel = channel,
            requestedFormats = null,
        )
    }
}
