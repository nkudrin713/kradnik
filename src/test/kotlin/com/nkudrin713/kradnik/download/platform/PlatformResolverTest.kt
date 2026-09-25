package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformResolverTest {
    @Test
    fun rejectsUnknownPlatform() {
        assertFailsWith<UnsupportedPlatformException> {
            PlatformResolver().resolve("https://example.com/video")
        }
    }

    @Test
    fun youtubeBuildsVideoAndAudioSpecs() {
        val specs = PlatformResolver().resolve("https://youtube.com/watch?v=id")

        assertEquals(OutputType.VIDEO, specs.video.outputType)
        assertEquals("youtube_h264_mobile_2gb", specs.video.presetName)
        assertEquals(OutputType.AUDIO, specs.audio.outputType)
        assertEquals("youtube_audio", specs.audio.presetName)
        assertEquals(listOf("--merge-output-format", "mp4"), specs.video.extraArgs)
    }

    @Test
    fun instagramBuildsVideoAndAudioSpecs() {
        val specs = PlatformResolver().resolve(
            "https://www.instagram.com/reel/abc/?igshid=tracking",
        )

        assertEquals("https://www.instagram.com/reel/abc/", specs.video.normalizedUrl)
        assertEquals(DownloadPlatform.INSTAGRAM, specs.video.platform)
        assertEquals("instagram_mobile_video", specs.video.presetName)
        assertEquals("instagram_audio", specs.audio.presetName)
    }
}
