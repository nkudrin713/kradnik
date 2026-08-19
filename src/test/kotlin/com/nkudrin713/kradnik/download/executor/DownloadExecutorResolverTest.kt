package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.request.DownloadRequest
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertSame

class DownloadExecutorResolverTest {
    @Test
    fun resolvesFirstSupportingExecutor() {
        val request = request()
        val first: DownloadExecutor = mockk {
            every { supports(request) } returns false
        }
        val second: DownloadExecutor = mockk {
            every { supports(request) } returns true
        }

        assertSame(second, DownloadExecutorResolver(listOf(first, second)).resolve(request))
    }

    private fun request(): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            outputType = OutputType.VIDEO,
            formatSelector = "format",
            presetName = "preset",
        )
    }
}
