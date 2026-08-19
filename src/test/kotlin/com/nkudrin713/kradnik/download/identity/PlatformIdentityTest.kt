package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.InstagramDownloadHandler
import com.nkudrin713.kradnik.download.platform.PlatformFeatureToggles
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.platform.YouTubeDownloadHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformIdentityTest {
    private val resolver = PlatformResolver(
        handlers = listOf(YouTubeDownloadHandler(), InstagramDownloadHandler()),
        platformFeatureToggles = PlatformFeatureToggles(
            youtubeEnabled = true,
            instagramEnabled = true,
        ),
    )

    @Test
    fun resolvesYouTubeWatchUrl() {
        val actual = resolver.resolve(
            url = "https://www.youtube.com/watch?v=abc&utm_source=x",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.youtube.com/watch?v=abc&utm_source=x", actual.originalUrl)
        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeShortUrlWithTimecode() {
        val actual = resolver.resolve(
            url = "https://youtu.be/abc?t=42",
            outputType = OutputType.AUDIO,
        ).identity

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:audio:youtube_audio", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeShortsUrl() {
        val actual = resolver.resolve(
            url = "https://youtube.com/shorts/abc?si=tracking",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeLiveUrl() {
        val actual = resolver.resolve(
            url = "https://youtube.com/live/abc",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeMusicUrl() {
        val actual = resolver.resolve(
            url = "https://music.youtube.com/watch?v=abc",
            outputType = OutputType.AUDIO,
        ).identity

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:audio:youtube_audio", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeEmbedUrl() {
        val actual = resolver.resolve(
            url = "https://www.youtube.com/embed/abc",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile", actual.cacheKey)
    }

    @Test
    fun rejectsPlaylistOnlyUrl() {
        assertFailsWith<UnsupportedUrlException> {
            resolver.resolve(
                url = "https://www.youtube.com/playlist?list=PL123",
                outputType = OutputType.VIDEO,
            )
        }
    }

    @Test
    fun resolvesInstagramReelUrl() {
        val actual = resolver.resolve(
            url = "https://www.instagram.com/reel/abc/?igshid=tracking",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.instagram.com/reel/abc/?igshid=tracking", actual.originalUrl)
        assertEquals("https://www.instagram.com/reel/abc/", actual.normalizedUrl)
        assertEquals("instagram:reel:abc:video:instagram_mobile_video", actual.cacheKey)
    }

    @Test
    fun resolvesInstagramPostUrl() {
        val actual = resolver.resolve(
            url = "https://m.instagram.com/p/abc/?utm_source=x",
            outputType = OutputType.AUDIO,
        ).identity

        assertEquals("https://www.instagram.com/p/abc/", actual.normalizedUrl)
        assertEquals("instagram:p:abc:audio:instagram_audio", actual.cacheKey)
    }

    @Test
    fun resolvesInstagramStoryUrl() {
        val actual = resolver.resolve(
            url = "https://www.instagram.com/stories/user/123456789/",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.instagram.com/stories/user/123456789/", actual.normalizedUrl)
        assertEquals("instagram:story:user:123456789:video:instagram_mobile_video", actual.cacheKey)
    }

    @Test
    fun resolvesUnknownInstagramUrlWithGenericNormalizedKey() {
        val actual = resolver.resolve(
            url = "https://www.instagram.com/user/?igshid=x",
            outputType = OutputType.VIDEO,
        ).identity

        assertEquals("https://www.instagram.com/user/", actual.normalizedUrl)
        assertEquals(
            "instagram:https://www.instagram.com/user/:video:instagram_mobile_video",
            actual.cacheKey,
        )
    }

    @Test
    fun rejectsUnsupportedUrl() {
        assertFailsWith<UnsupportedPlatformException> {
            resolver.resolve(
                url = "https://example.com/video",
                outputType = OutputType.VIDEO,
            )
        }
    }
}
