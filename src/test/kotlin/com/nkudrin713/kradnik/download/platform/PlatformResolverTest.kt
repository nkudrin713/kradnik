package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.request.DownloadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PlatformResolverTest {
    @Test
    fun resolvesFirstSupportedHandler() {
        val first = handler(supported = false)
        val second = handler(supported = true)
        val fallback = handler(supported = true)
        val resolver = PlatformResolver(listOf(first, second, fallback))

        val actual = resolver.resolve("https://example.com/video")

        assertSame(second, actual)
    }

    @Test
    fun throwsWhenNoHandlerSupportsUrl() {
        val resolver = PlatformResolver(listOf(handler(supported = false)))

        assertFailsWith<IllegalStateException> {
            resolver.resolve("https://example.com/video")
        }
    }

    @Test
    fun youtubeHandlerSupportsExpectedHosts() {
        val handler = YouTubeDownloadHandler()

        assertEquals(true, handler.supports("https://youtube.com/watch?v=id"))
        assertEquals(true, handler.supports("https://youtu.be/id"))
        assertEquals(false, handler.supports("https://example.com/video"))
    }

    @Test
    fun youtubeHandlerBuildsAudioRequest() {
        val actual = YouTubeDownloadHandler().buildRequest(
            url = "https://youtube.com/watch?v=id",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://youtube.com/watch?v=id", actual.originalUrl)
        assertEquals("https://youtube.com/watch?v=id", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("youtube_audio", actual.presetName)
        assertEquals("ba/bestaudio", actual.formatSelector)
        assertEquals(listOf(
            "-x",
            "--audio-format", "mp3",
            "--embed-metadata",
            "--embed-thumbnail",
            "--convert-thumbnails", "jpg",
        ), actual.extraArgs)
    }

    @Test
    fun youtubeHandlerBuildsVideoRequest() {
        val actual = YouTubeDownloadHandler().buildRequest(
            url = "https://youtube.com/watch?v=id",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://youtube.com/watch?v=id", actual.originalUrl)
        assertEquals(OutputType.VIDEO, actual.outputType)
        assertEquals("youtube_h264_mobile", actual.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), actual.extraArgs)
        assertEquals(true, actual.formatSelector.contains("height<=1280"))
    }

    @Test
    fun instagramHandlerSupportsExpectedHosts() {
        val handler = InstagramDownloadHandler()

        assertEquals(true, handler.supports("https://www.instagram.com/reel/abc/"))
        assertEquals(true, handler.supports("https://m.instagram.com/p/abc/"))
        assertEquals(false, handler.supports("https://example.com/reel/abc/"))
    }

    @Test
    fun instagramHandlerBuildsVideoRequest() {
        val actual = InstagramDownloadHandler().buildRequest(
            url = "https://www.instagram.com/reel/abc/?igshid=tracking",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://www.instagram.com/reel/abc/?igshid=tracking", actual.originalUrl)
        assertEquals("https://www.instagram.com/reel/abc/", actual.normalizedUrl)
        assertEquals(OutputType.VIDEO, actual.outputType)
        assertEquals("instagram_mobile_video", actual.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), actual.extraArgs)
        assertEquals(true, actual.formatSelector.contains("b[height<=1280][ext=mp4]"))
    }

    @Test
    fun instagramHandlerBuildsAudioRequest() {
        val actual = InstagramDownloadHandler().buildRequest(
            url = "https://www.instagram.com/p/abc/",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://www.instagram.com/p/abc/", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("instagram_audio", actual.presetName)
        assertEquals("ba/bestaudio/best", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    @Test
    fun defaultHandlerBuildsVideoRequest() {
        val actual = DefaultYtDlpDownloadHandler().buildRequest(
            url = "https://example.com/video",
            outputType = OutputType.VIDEO,
        )

        assertEquals("https://example.com/video", actual.originalUrl)
        assertEquals("https://example.com/video", actual.normalizedUrl)
        assertEquals(OutputType.VIDEO, actual.outputType)
        assertEquals("default_mobile_video", actual.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), actual.extraArgs)
        assertEquals(true, actual.formatSelector.contains("height<=1280"))
    }

    @Test
    fun defaultHandlerSupportsAndNormalizesAnyUrl() {
        val handler = DefaultYtDlpDownloadHandler()

        assertEquals(true, handler.supports("https://example.com/video"))
        assertEquals("https://example.com/video", handler.normalize("https://example.com/video"))
    }

    @Test
    fun defaultHandlerBuildsAudioRequest() {
        val actual = DefaultYtDlpDownloadHandler().buildRequest(
            url = "https://example.com/audio",
            outputType = OutputType.AUDIO,
        )

        assertEquals("https://example.com/audio", actual.originalUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("default_audio", actual.presetName)
        assertEquals("ba/bestaudio", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    private fun handler(supported: Boolean): PlatformDownloadHandler {
        return object : PlatformDownloadHandler {
            override fun supports(url: String): Boolean = supported

            override fun normalize(url: String): String = url

            override fun buildRequest(
                url: String,
                outputType: OutputType,
            ): DownloadRequest {
                return DownloadRequest(
                    originalUrl = url,
                    normalizedUrl = url,
                    outputType = outputType,
                    formatSelector = "format",
                    presetName = "preset",
                )
            }
        }
    }
}
