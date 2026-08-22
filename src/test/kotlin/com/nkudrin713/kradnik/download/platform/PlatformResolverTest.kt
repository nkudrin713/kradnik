package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.request.DownloadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformResolverTest {
    @Test
    fun resolvesFirstSupportedHandler() {
        val first = handler(supported = false, platform = DownloadPlatform.YOUTUBE)
        val second = handler(supported = true, platform = DownloadPlatform.INSTAGRAM)
        val resolver = PlatformResolver(listOf(first, second), toggles(instagramEnabled = true))

        val actual = resolver.resolve("https://example.com/video", OutputType.VIDEO)

        assertEquals("preset-instagram", actual.request.presetName)
    }

    @Test
    fun throwsWhenNoHandlerSupportsUrl() {
        val resolver = PlatformResolver(
            listOf(handler(supported = false, platform = DownloadPlatform.YOUTUBE)),
            toggles(),
        )

        assertFailsWith<UnsupportedPlatformException> {
            resolver.resolve("https://example.com/video", OutputType.VIDEO)
        }
    }

    @Test
    fun throwsWhenResolvedPlatformIsDisabled() {
        val resolver = PlatformResolver(
            listOf(
                handler(supported = true, platform = DownloadPlatform.INSTAGRAM),
            ),
            toggles(instagramEnabled = false),
        )

        val exception = assertFailsWith<UnsupportedPlatformException> {
            resolver.resolve("https://www.instagram.com/reel/abc/", OutputType.VIDEO)
        }

        assertEquals(
            "Платформа не поддерживается. Доступные платформы: YouTube.",
            exception.message,
        )
    }

    @Test
    fun throwsWhenVkIsDisabled() {
        val resolver = PlatformResolver(
            handlers = listOf(VkDownloadHandler()),
            platformFeatureToggles = toggles(vkEnabled = false),
        )

        val exception = assertFailsWith<UnsupportedPlatformException> {
            resolver.resolve("https://vk.com/video-1_2", OutputType.VIDEO)
        }

        assertEquals(
            "Платформа не поддерживается. Доступные платформы: YouTube.",
            exception.message,
        )
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
        val actual = YouTubeDownloadHandler().resolve(
            url = "https://youtube.com/watch?v=id",
            outputType = OutputType.AUDIO,
        ).request

        assertEquals("https://youtube.com/watch?v=id", actual.originalUrl)
        assertEquals("https://www.youtube.com/watch?v=id", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("youtube_audio", actual.presetName)
        assertEquals("ba/bestaudio", actual.formatSelector)
        assertEquals(
            listOf(
                "-x",
                "--audio-format", "mp3",
                "--embed-metadata",
                "--embed-thumbnail",
                "--convert-thumbnails", "jpg",
            ),
            actual.extraArgs,
        )
    }

    @Test
    fun youtubeHandlerBuildsVideoRequest() {
        val actual = YouTubeDownloadHandler().resolve(
            url = "https://youtube.com/watch?v=id",
            outputType = OutputType.VIDEO,
        ).request

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
        val actual = InstagramDownloadHandler().resolve(
            url = "https://www.instagram.com/reel/abc/?igshid=tracking",
            outputType = OutputType.VIDEO,
        ).request

        assertEquals("https://www.instagram.com/reel/abc/?igshid=tracking", actual.originalUrl)
        assertEquals("https://www.instagram.com/reel/abc/", actual.normalizedUrl)
        assertEquals(OutputType.VIDEO, actual.outputType)
        assertEquals("instagram_mobile_video", actual.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), actual.extraArgs)
        assertEquals(true, actual.formatSelector.contains("vcodec^=avc1"))
        assertEquals(true, actual.formatSelector.contains("acodec^=mp4a"))
    }

    @Test
    fun instagramHandlerBuildsAudioRequest() {
        val actual = InstagramDownloadHandler().resolve(
            url = "https://www.instagram.com/p/abc/",
            outputType = OutputType.AUDIO,
        ).request

        assertEquals("https://www.instagram.com/p/abc/", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("instagram_audio", actual.presetName)
        assertEquals("ba/bestaudio/best", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    private fun handler(
        supported: Boolean,
        platform: DownloadPlatform,
    ): PlatformDownloadHandler {
        return object : PlatformDownloadHandler {
            override val platform: DownloadPlatform = platform

            override fun supports(url: String): Boolean = supported

            override fun resolve(
                url: String,
                outputType: OutputType,
            ): ResolvedDownload {
                val request = DownloadRequest(
                    originalUrl = url,
                    normalizedUrl = url,
                    outputType = outputType,
                    formatSelector = "format",
                    presetName = "preset-${platform.name.lowercase()}",
                )
                return ResolvedDownload(
                    identity = DownloadIdentity(
                        originalUrl = url,
                        normalizedUrl = url,
                        cacheKey = "cache-key",
                    ),
                    request = request,
                )
            }
        }
    }

    private fun toggles(
        youtubeEnabled: Boolean = true,
        instagramEnabled: Boolean = false,
        vkEnabled: Boolean = false,
    ): PlatformFeatureToggles {
        return PlatformFeatureToggles(
            youtubeEnabled = youtubeEnabled,
            instagramEnabled = instagramEnabled,
            vkEnabled = vkEnabled,
        )
    }
}
