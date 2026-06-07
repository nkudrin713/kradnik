package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlIdentityResolverTest {
    private val youtubeResolver = YouTubeUrlIdentityResolver()
    private val genericResolver = GenericUrlIdentityResolver()
    private val resolver = UrlIdentityResolver(listOf(youtubeResolver, genericResolver))

    @Test
    fun resolvesYouTubeWatchUrl() {
        val actual = resolver.resolve(
            url = "https://www.youtube.com/watch?v=abc&utm_source=x",
            outputType = OutputType.VIDEO,
            presetName = "youtube_h264_mobile",
        )

        assertEquals("https://www.youtube.com/watch?v=abc&utm_source=x", actual.originalUrl)
        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeShortUrlWithTimecode() {
        val actual = resolver.resolve(
            url = "https://youtu.be/abc?t=42",
            outputType = OutputType.AUDIO,
            presetName = "youtube_audio",
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:audio:youtube_audio", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeShortsUrl() {
        val actual = resolver.resolve(
            url = "https://youtube.com/shorts/abc?si=tracking",
            outputType = OutputType.VIDEO,
            presetName = "youtube_h264_mobile",
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeLiveUrl() {
        val actual = resolver.resolve(
            url = "https://youtube.com/live/abc",
            outputType = OutputType.VIDEO,
            presetName = "youtube_h264_mobile",
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeMusicUrl() {
        val actual = resolver.resolve(
            url = "https://music.youtube.com/watch?v=abc",
            outputType = OutputType.AUDIO,
            presetName = "youtube_audio",
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:audio:youtube_audio", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeEmbedUrl() {
        val actual = resolver.resolve(
            url = "https://www.youtube.com/embed/abc",
            outputType = OutputType.VIDEO,
            presetName = "youtube_h264_mobile",
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun rejectsPlaylistOnlyUrl() {
        assertFailsWith<UnsupportedUrlException> {
            resolver.resolve(
                url = "https://www.youtube.com/playlist?list=PL123",
                outputType = OutputType.VIDEO,
                presetName = "youtube_h264_mobile",
            )
        }
    }

    @Test
    fun resolvesGenericUrl() {
        val actual = genericResolver.resolve(
            url = " https://Example.com:443/video?b=2&utm_source=x&a=1#fragment ",
            outputType = OutputType.VIDEO,
            presetName = "default_mobile_video",
        )

        assertEquals("https://Example.com:443/video?b=2&utm_source=x&a=1#fragment", actual.originalUrl)
        assertEquals("https://example.com/video?a=1&b=2", actual.normalizedUrl)
        assertEquals("generic:video:default_mobile_video:https://example.com/video?a=1&b=2", actual.cacheKey)
    }

    @Test
    fun rejectsNonHttpUrl() {
        assertFailsWith<UnsupportedUrlException> {
            genericResolver.resolve(
                url = "ftp://example.com/video",
                outputType = OutputType.VIDEO,
                presetName = "default_mobile_video",
            )
        }
    }

    @Test
    fun usesGenericResolverForNonYouTubeUrl() {
        val actual = resolver.resolve(
            url = "https://example.com/video",
            outputType = OutputType.VIDEO,
            presetName = "default_mobile_video",
        )

        assertEquals("generic:video:default_mobile_video:https://example.com/video", actual.cacheKey)
    }
}
