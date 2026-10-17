package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.testPlatformResolver
import com.nkudrin713.kradnik.download.vk.VK_AUDIO_PRESET
import com.nkudrin713.kradnik.download.vk.VK_VIDEO_PRESET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VkPlatformResolverTest {
    private val resolver = testPlatformResolver()

    @Test
    fun wallLinksHaveOnePostIdentityAndDoNotSelectAnAttachedVideo() {
        for (url in listOf("https://vk.com/wall-1_2", "https://m.vk.ru/wall-1_2?z=video-3_4", "https://vk.com/community?w=wall%2D1_2")) {
            val spec = resolver.resolve(url).video
            assertEquals("https://vk.com/wall-1_2", spec.normalizedUrl)
            assertEquals(OutputType.POST, spec.outputType)
            assertEquals("vk:wall:-1_2:post:vk_post", spec.cacheKey)
            assertEquals(url, spec.originalUrl)
        }
        for (url in listOf("https://vk.com/wall-1_2/other", "https://vk.com/wall-1_0", "https://vkvideo.ru/wall-1_2", "https://user@vk.com/wall-1_2")) {
            assertFailsWith<UnsupportedUrlException> { resolver.resolve(url) }
        }
    }

    @Test
    fun supportsExpectedHosts() {
        assertEquals(DownloadPlatform.VK, resolver.resolve("https://vk.com/video-1_2").video.platform)
        assertEquals(DownloadPlatform.VK, resolver.resolve("https://m.vk.ru/clip1_2").video.platform)
        assertEquals(DownloadPlatform.VK, resolver.resolve("https://vkvideo.ru/video-1_2").video.platform)
        assertEquals(DownloadPlatform.VK, resolver.resolve("https://vksport.vkvideo.ru/video-1_2").video.platform)
        assertFailsWith<UnsupportedPlatformException> { resolver.resolve("https://www.vk.com/video-1_2") }
        assertFailsWith<UnsupportedPlatformException> { resolver.resolve("https://evilvk.com/video-1_2") }
        assertFailsWith<UnsupportedPlatformException> { resolver.resolve("https://vk.com.example.org/video-1_2") }
    }

    @Test
    fun buildsVideoRequest() {
        val actual = resolver.resolve(
            "https://new.vk.com/video-123_456?utm_source=test",
        ).video

        assertEquals("https://new.vk.com/video-123_456?utm_source=test", actual.originalUrl)
        assertEquals("https://vk.com/video-123_456", actual.normalizedUrl)
        assertEquals(OutputType.VIDEO, actual.outputType)
        assertEquals(DownloadPlatform.VK, actual.platform)
        assertEquals(VK_VIDEO_PRESET, actual.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), actual.extraArgs)
        assertEquals(
            "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a]/" +
                "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                "b[height<=1280]/best",
            actual.formatSelector,
        )
    }

    @Test
    fun buildsAudioRequestForClip() {
        val actual = resolver.resolve("https://vk.ru/clip30014565_456240946").audio

        assertEquals("https://vk.com/clip30014565_456240946", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals(DownloadPlatform.VK, actual.platform)
        assertEquals(VK_AUDIO_PRESET, actual.presetName)
        assertEquals("ba/bestaudio/best", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    @Test
    fun directVideoKeepsAccessParametersAndTakesPriorityOverQueryTarget() {
        val url = "https://vk.com/video-1_2?list=access_key&z=video-3_4"
        val specs = resolver.resolve(" $url ")

        assertEquals(url, specs.video.originalUrl)
        assertEquals(url, specs.audio.originalUrl)
        assertEquals("https://vk.com/video-1_2", specs.video.normalizedUrl)
        assertEquals(specs.video.normalizedUrl, specs.audio.normalizedUrl)
        assertEquals("vk:video:-1_2:video:$VK_VIDEO_PRESET", specs.video.cacheKey)
        assertEquals("vk:video:-1_2:audio:$VK_AUDIO_PRESET", specs.audio.cacheKey)
    }

    @Test
    fun resolvesEncodedQueryTarget() {
        val actual = resolver.resolve(
            "https://vk.com/clips-74006511?z=clip-74006511_456247211%2Fpl_-74006511_-2",
        ).video

        assertEquals("https://vk.com/clip-74006511_456247211", actual.normalizedUrl)
        assertEquals(
            "vk:clip:-74006511_456247211:video:$VK_VIDEO_PRESET",
            actual.cacheKey,
        )
    }

    @Test
    fun resolvesVideoQueryTarget() {
        val actual = resolver.resolve(
            "https://vk.com/feed?z=video-43215063_166094326%2Fbb50cacd3177146d7a",
        ).video

        assertEquals("https://vk.com/video-43215063_166094326", actual.normalizedUrl)
        assertEquals(
            "vk:video:-43215063_166094326:video:$VK_VIDEO_PRESET",
            actual.cacheKey,
        )
    }

    @Test
    fun rejectsUnsupportedVkPages() {
        val urls = listOf(
            "https://vk.com/video/@channel/all",
            "https://vkvideo.ru/playlist/-1_2",
            "https://vk.com/audio",
        )

        urls.forEach { url ->
            assertFailsWith<UnsupportedUrlException>(url) {
                resolver.resolve(url)
            }
        }
    }
}
