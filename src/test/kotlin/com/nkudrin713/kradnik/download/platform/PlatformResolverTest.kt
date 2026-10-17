package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.testPlatformResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformResolverTest {
    @Test
    fun rejectsUnknownPlatform() {
        assertFailsWith<UnsupportedPlatformException> {
            testPlatformResolver().resolve("https://example.com/video")
        }
    }

    @Test
    fun youtubeBuildsVideoAndAudioSpecs() {
        val specs = testPlatformResolver().resolve("https://youtube.com/watch?v=id")

        assertEquals(OutputType.VIDEO, specs.video.outputType)
        assertEquals("youtube_h264_mobile_2gb", specs.video.presetName)
        assertEquals(OutputType.AUDIO, specs.audio.outputType)
        assertEquals("youtube_audio", specs.audio.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), specs.video.extraArgs)
    }

    @Test
    fun youtubeAliasesPreserveOriginalUrlsAndShareVideoAndAudioIdentities() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=abc&list=PL123",
            "https://m.youtube.com/watch?v=abc&t=42",
            "https://music.youtube.com/watch?v=abc",
            "https://youtu.be/abc?si=tracking",
            "https://youtube.com/shorts/abc",
            "https://youtube.com/live/abc",
            "https://youtube.com/embed/abc",
            "https://youtube.com/v/abc",
        )
        for (url in urls) {
            val specs = testPlatformResolver().resolve(" $url ")
            assertEquals(url, specs.video.originalUrl)
            assertEquals(url, specs.audio.originalUrl)
            assertEquals("https://www.youtube.com/watch?v=abc", specs.video.normalizedUrl)
            assertEquals(specs.video.normalizedUrl, specs.audio.normalizedUrl)
            assertEquals("youtube:video:abc:video:youtube_h264_mobile_2gb", specs.video.cacheKey)
            assertEquals("youtube:video:abc:audio:youtube_audio", specs.audio.cacheKey)
        }
    }

    @Test
    fun recognizesPlatformsBeforeRejectingUnsupportedResourcePaths() {
        val urls = mapOf(
            "https://youtube.com/playlist?list=PL123" to DownloadPlatform.YOUTUBE,
            "https://instagram.com/stories/user/123" to DownloadPlatform.INSTAGRAM,
            "https://vk.com/audio" to DownloadPlatform.VK,
            "https://threads.net/@user" to DownloadPlatform.THREADS,
        )
        val resolver = testPlatformResolver()
        for ((url, platform) in urls) {
            assertEquals(platform, resolver.platformOf(url))
            assertFailsWith<UnsupportedUrlException> { resolver.resolve(url) }
        }
        assertFailsWith<UnsupportedUrlException> { resolver.resolve("ftp://youtube.com/watch?v=abc") }
    }

    @Test
    fun instagramBuildsVideoAndAudioSpecs() {
        val specs = testPlatformResolver().resolve(
            "https://www.instagram.com/reel/abc/?igshid=tracking",
        )

        assertEquals("https://www.instagram.com/reel/abc/", specs.video.normalizedUrl)
        assertEquals(DownloadPlatform.INSTAGRAM, specs.video.platform)
        assertEquals("instagram_mobile_video", specs.video.presetName)
        assertEquals("instagram_audio", specs.audio.presetName)
    }
}
