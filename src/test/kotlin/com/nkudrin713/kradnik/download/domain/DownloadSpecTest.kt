package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSpecTest {
    @Test
    fun createsSpecFromJobSnapshot() {
        val actual = DownloadSpec.fromJob(
            DownloadJob(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                cacheKey = "cache-key",
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.YOUTUBE,
                downloadPreset = "preset",
                selectedFormat = "format",
                downloadExtraArgs = listOf("-x", "--audio-format", "mp3"),
            )
        )

        assertEquals("https://example.com/raw", actual.originalUrl)
        assertEquals("https://example.com/normalized", actual.normalizedUrl)
        assertEquals("cache-key", actual.cacheKey)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, actual.platform)
        assertEquals("preset", actual.presetName)
        assertEquals("format", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    @Test
    fun addsAudioQuality() {
        val actual = spec(
            extraArgs = listOf("-x", "--audio-format", "mp3"),
        ).withAudioQuality("40K")

        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"), actual.extraArgs)
    }

    @Test
    fun replacesAudioQuality() {
        val actual = spec(
            extraArgs = listOf("-x", "--audio-format", "mp3", "--audio-quality", "96K"),
        ).withAudioQuality("40K")

        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"), actual.extraArgs)
    }

    private fun spec(extraArgs: List<String>): DownloadSpec {
        return DownloadSpec(
            originalUrl = "https://example.com/raw",
            normalizedUrl = "https://example.com/normalized",
            cacheKey = "cache-key",
            outputType = OutputType.AUDIO,
            platform = DownloadPlatform.YOUTUBE,
            formatSelector = "format",
            extraArgs = extraArgs,
            presetName = "preset",
        )
    }
}
