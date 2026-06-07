package com.nkudrin713.kradnik.download.request

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
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
                downloadPreset = "preset",
                selectedFormat = "format",
                downloadExtraArgs = listOf("-x", "--audio-format", "mp3"),
            )
        )

        assertEquals("https://example.com/raw", actual.originalUrl)
        assertEquals("https://example.com/normalized", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("preset", actual.presetName)
        assertEquals("format", actual.formatSelector)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.extraArgs)
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
}
