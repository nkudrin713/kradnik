package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformResolverTest {
    @Test
    fun resolvesFirstSupportedPlatform() {
        val first = platform(supported = false, id = DownloadPlatform.YOUTUBE)
        val second = platform(supported = true, id = DownloadPlatform.INSTAGRAM)
        val resolver = PlatformResolver(listOf(first, second))

        val actual = resolver.resolve("https://example.com/video")

        assertEquals(DownloadPlatform.INSTAGRAM, actual.video.platform)
    }

    @Test
    fun listsSupportedPlatformsWhenUrlIsUnknown() {
        val resolver = PlatformResolver(
            listOf(platform(supported = false, id = DownloadPlatform.YOUTUBE))
        )

        val exception = assertFailsWith<UnsupportedPlatformException> {
            resolver.resolve("https://example.com/video")
        }

        assertEquals(
            "Платформа не поддерживается. Доступные платформы: YouTube, Instagram, VK.",
            exception.message,
        )
    }

    @Test
    fun youtubeBuildsVideoAndAudioSpecs() {
        val specs = YouTubeDownloadHandler().resolve("https://youtube.com/watch?v=id")

        assertEquals(OutputType.VIDEO, specs.video.outputType)
        assertEquals("youtube_h264_mobile_2gb", specs.video.presetName)
        assertEquals(OutputType.AUDIO, specs.audio.outputType)
        assertEquals("youtube_audio", specs.audio.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), specs.video.extraArgs)
    }

    @Test
    fun instagramBuildsVideoAndAudioSpecs() {
        val specs = InstagramDownloadHandler().resolve(
            "https://www.instagram.com/reel/abc/?igshid=tracking"
        )

        assertEquals("https://www.instagram.com/reel/abc/", specs.video.normalizedUrl)
        assertEquals(DownloadPlatform.INSTAGRAM, specs.video.platform)
        assertEquals("instagram_mobile_video", specs.video.presetName)
        assertEquals("instagram_audio", specs.audio.presetName)
    }

    private fun platform(
        supported: Boolean,
        id: DownloadPlatform,
    ): PlatformDownloadHandler {
        return object : PlatformDownloadHandler {
            override val platform = id

            override fun supports(url: String): Boolean = supported

            override fun resolve(url: String): PlatformDownloadSpecs {
                return PlatformDownloadSpecs(
                    video = spec(url, OutputType.VIDEO, id),
                    audio = spec(url, OutputType.AUDIO, id),
                )
            }
        }
    }

    private fun spec(
        url: String,
        outputType: OutputType,
        platform: DownloadPlatform,
    ): DownloadSpec {
        return DownloadSpec(
            originalUrl = url,
            normalizedUrl = url,
            cacheKey = "cache-key",
            outputType = outputType,
            platform = platform,
            formatSelector = "format",
            presetName = "preset",
        )
    }
}
