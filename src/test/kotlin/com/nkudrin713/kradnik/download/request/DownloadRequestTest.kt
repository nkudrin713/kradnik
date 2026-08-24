package com.nkudrin713.kradnik.download.request

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadRequestTest {
    @Test
    fun createsRequestFromJobSnapshot() {
        val actual = DownloadRequest.fromJob(
            DownloadJob(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                outputType = OutputType.AUDIO,
                downloadStrategy = DownloadStrategy.YOUTUBE_YT_DLP,
                downloadPreset = "preset",
                selectedFormat = "format",
                downloadExtraArgs = listOf("-x", "--audio-format", "mp3"),
            )
        )

        assertEquals("https://example.com/raw", actual.originalUrl)
        assertEquals("https://example.com/normalized", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals(DownloadStrategy.YOUTUBE_YT_DLP, actual.strategy)
        assertEquals("preset", actual.presetName)
        assertEquals("format", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
    }

    @Test
    fun addsAudioQuality() {
        val actual = request(
            extraArgs = listOf("-x", "--audio-format", "mp3"),
        ).withAudioQuality("40K")

        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"), actual.extraArgs)
    }

    @Test
    fun replacesAudioQuality() {
        val actual = request(
            extraArgs = listOf("-x", "--audio-format", "mp3", "--audio-quality", "96K"),
        ).withAudioQuality("40K")

        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"), actual.extraArgs)
    }

    @Test
    fun failsWhenSelectedFormatIsMissing() {
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest.fromJob(
                DownloadJob(
                    originalUrl = "https://example.com/raw",
                    normalizedUrl = "https://example.com/normalized",
                    outputType = OutputType.VIDEO,
                    selectedFormat = null,
                )
            )
        }
    }

    private fun request(extraArgs: List<String>): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://example.com/raw",
            normalizedUrl = "https://example.com/normalized",
            outputType = OutputType.AUDIO,
            strategy = DownloadStrategy.YT_DLP,
            formatSelector = "format",
            extraArgs = extraArgs,
            presetName = "preset",
        )
    }
}
