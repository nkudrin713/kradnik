package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VkDownloadHandlerTest {
    private val handler = VkDownloadHandler()

    @Test
    fun supportsExpectedHosts() {
        assertTrue(handler.supports("https://vk.com/video-1_2"))
        assertTrue(handler.supports("https://m.vk.ru/clip1_2"))
        assertTrue(handler.supports("https://vkvideo.ru/video-1_2"))
        assertTrue(handler.supports("https://vksport.vkvideo.ru/video-1_2"))
        assertFalse(handler.supports("https://www.vk.com/video-1_2"))
        assertFalse(handler.supports("https://evilvk.com/video-1_2"))
        assertFalse(handler.supports("https://vk.com.example.org/video-1_2"))
    }

    @Test
    fun buildsVideoRequest() {
        val actual = handler.resolve(
            url = "https://new.vk.com/video-123_456?utm_source=test",
            outputType = OutputType.VIDEO,
        ).request

        assertEquals("https://new.vk.com/video-123_456?utm_source=test", actual.originalUrl)
        assertEquals("https://vk.com/video-123_456", actual.normalizedUrl)
        assertEquals(OutputType.VIDEO, actual.outputType)
        assertEquals(VK_VIDEO_PRESET, actual.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), actual.extraArgs)
        assertTrue(actual.formatSelector.contains("height<=1280"))
        assertTrue(actual.formatSelector.contains("vcodec^=avc1"))
    }

    @Test
    fun buildsAudioRequestForClip() {
        val actual = handler.resolve(
            url = "https://vk.ru/clip30014565_456240946",
            outputType = OutputType.AUDIO,
        ).request

        assertEquals("https://vk.com/clip30014565_456240946", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals(VK_AUDIO_PRESET, actual.presetName)
        assertEquals("ba/bestaudio/best", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    @Test
    fun resolvesEncodedQueryTarget() {
        val actual = handler.resolve(
            url = "https://vk.com/clips-74006511?z=clip-74006511_456247211%2Fpl_-74006511_-2",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://vk.com/clip-74006511_456247211", actual.identity.normalizedUrl)
        assertEquals(
            "vk:clip:-74006511_456247211:video:$VK_VIDEO_PRESET",
            actual.identity.cacheKey,
        )
    }

    @Test
    fun resolvesVideoQueryTarget() {
        val actual = handler.resolve(
            url = "https://vk.com/feed?z=video-43215063_166094326%2Fbb50cacd3177146d7a",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://vk.com/video-43215063_166094326", actual.identity.normalizedUrl)
        assertEquals(
            "vk:video:-43215063_166094326:video:$VK_VIDEO_PRESET",
            actual.identity.cacheKey,
        )
    }

    @Test
    fun rejectsUnsupportedVkPages() {
        val urls = listOf(
            "https://vk.com/video/@channel/all",
            "https://vkvideo.ru/playlist/-1_2",
            "https://vk.com/wall-1_2?z=video-1_2",
            "https://vk.com/audio",
            "https://live.vkvideo.ru/channel",
        )

        urls.forEach { url ->
            assertFailsWith<UnsupportedUrlException>(url) {
                handler.resolve(url, OutputType.VIDEO)
            }
        }
    }
}
