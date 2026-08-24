package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.InstagramDownloadHandler
import com.nkudrin713.kradnik.download.platform.PlatformFeatureToggles
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.platform.VK_AUDIO_PRESET
import com.nkudrin713.kradnik.download.platform.VK_VIDEO_PRESET
import com.nkudrin713.kradnik.download.platform.VkDownloadHandler
import com.nkudrin713.kradnik.download.platform.YouTubeDownloadHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformIdentityTest {
    private val resolver = PlatformResolver(
        handlers = listOf(YouTubeDownloadHandler(), InstagramDownloadHandler(), VkDownloadHandler()),
        platformFeatureToggles = PlatformFeatureToggles(
            youtubeEnabled = true,
            instagramEnabled = true,
            vkEnabled = true,
        ),
    )

    @Test
    fun resolvesYouTubeWatchUrl() {
        val actual = resolver.resolve(
            url = "https://www.youtube.com/watch?v=abc&utm_source=x",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.youtube.com/watch?v=abc&utm_source=x", actual.originalUrl)
        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile_2gb", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeShortUrlWithTimecode() {
        val actual = resolver.resolve(
            url = "https://youtu.be/abc?t=42",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:audio:youtube_audio", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeShortsUrl() {
        val actual = resolver.resolve(
            url = "https://youtube.com/shorts/abc?si=tracking",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile_2gb", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeLiveUrl() {
        val actual = resolver.resolve(
            url = "https://youtube.com/live/abc",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile_2gb", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeMusicUrl() {
        val actual = resolver.resolve(
            url = "https://music.youtube.com/watch?v=abc",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:audio:youtube_audio", actual.cacheKey)
    }

    @Test
    fun resolvesYouTubeEmbedUrl() {
        val actual = resolver.resolve(
            url = "https://www.youtube.com/embed/abc",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.youtube.com/watch?v=abc", actual.normalizedUrl)
        assertEquals("youtube:video:abc:video:youtube_h264_mobile_2gb", actual.cacheKey)
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
        )

        assertEquals("https://www.instagram.com/reel/abc/?igshid=tracking", actual.originalUrl)
        assertEquals("https://www.instagram.com/reel/abc/", actual.normalizedUrl)
        assertEquals("instagram:reel:abc:video:instagram_mobile_video", actual.cacheKey)
    }

    @Test
    fun resolvesInstagramPostUrl() {
        val actual = resolver.resolve(
            url = "https://m.instagram.com/p/abc/?utm_source=x",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://www.instagram.com/p/abc/", actual.normalizedUrl)
        assertEquals("instagram:p:abc:audio:instagram_audio", actual.cacheKey)
    }

    @Test
    fun resolvesInstagramStoryUrl() {
        val actual = resolver.resolve(
            url = "https://www.instagram.com/stories/user/123456789/",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.instagram.com/stories/user/123456789/", actual.normalizedUrl)
        assertEquals("instagram:story:user:123456789:video:instagram_mobile_video", actual.cacheKey)
    }

    @Test
    fun resolvesUnknownInstagramUrlWithGenericNormalizedKey() {
        val actual = resolver.resolve(
            url = "https://www.instagram.com/user/?igshid=x",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.instagram.com/user/", actual.normalizedUrl)
        assertEquals(
            "instagram:https://www.instagram.com/user/:video:instagram_mobile_video",
            actual.cacheKey,
        )
    }

    @Test
    fun resolvesVkVideoUrl() {
        val actual = resolver.resolve(
            url = "https://vkvideo.ru/video-127553155_456242961?utm_source=x",
            outputType = OutputType.VIDEO,
        )

        assertEquals(
            "https://vkvideo.ru/video-127553155_456242961?utm_source=x",
            actual.originalUrl,
        )
        assertEquals("https://vk.com/video-127553155_456242961", actual.normalizedUrl)
        assertEquals(
            "vk:video:-127553155_456242961:video:$VK_VIDEO_PRESET",
            actual.cacheKey,
        )
    }

    @Test
    fun equivalentVkUrlsHaveSameCacheKey() {
        val direct = resolver.resolve(
            url = "https://vk.com/clip-74006511_456247211",
            outputType = OutputType.AUDIO,
        )
        val query = resolver.resolve(
            url = "https://vk.com/clips-74006511?z=clip-74006511_456247211%2Fpl_-74006511_-2",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://vk.com/clip-74006511_456247211", query.normalizedUrl)
        assertEquals("vk:clip:-74006511_456247211:audio:$VK_AUDIO_PRESET", direct.cacheKey)
        assertEquals(direct.cacheKey, query.cacheKey)
    }

    @Test
    fun vkVideoAndAudioHaveDifferentCacheKeys() {
        val video = resolver.resolve(
            url = "https://vk.com/video-1_2",
            outputType = OutputType.VIDEO,
        )
        val audio = resolver.resolve(
            url = "https://vk.com/video-1_2",
            outputType = OutputType.AUDIO,
        )

        assertEquals("vk:video:-1_2:video:$VK_VIDEO_PRESET", video.cacheKey)
        assertEquals("vk:video:-1_2:audio:$VK_AUDIO_PRESET", audio.cacheKey)
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
