package com.nkudrin713.kradnik.download.request

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.PlatformDownloadHandler
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadRequestFactoryTest {
    @Test
    fun createsRequestUsingResolvedHandler() {
        val factory = DownloadRequestFactory(
            PlatformResolver(listOf(handler()))
        )

        val actual = factory.create(
            DownloadJob(
                originalUrl = "https://example.com/raw",
                outputType = OutputType.AUDIO,
            )
        )

        assertEquals("https://example.com/raw", actual.originalUrl)
        assertEquals("https://example.com/normalized", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("test-preset", actual.presetName)
        assertEquals("test-format", actual.formatSelector)
    }

    private fun handler(): PlatformDownloadHandler {
        return object : PlatformDownloadHandler {
            override fun supports(url: String): Boolean = true

            override fun normalize(url: String): String = "https://example.com/normalized"

            override fun buildRequest(
                url: String,
                outputType: OutputType,
            ): DownloadRequest {
                return DownloadRequest(
                    originalUrl = url,
                    normalizedUrl = normalize(url),
                    outputType = outputType,
                    formatSelector = "test-format",
                    presetName = "test-preset",
                )
            }
        }
    }
}
