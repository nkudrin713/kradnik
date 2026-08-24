package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.request.DownloadRequest
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DownloadExecutorResolverTest {
    @Test
    fun resolvesExecutorByExactStrategy() {
        val request = request()
        val first: DownloadExecutor = mockk {
            every { strategies } returns setOf(DownloadStrategy.YOUTUBE_YT_DLP)
        }
        val second: DownloadExecutor = mockk {
            every { strategies } returns setOf(DownloadStrategy.VK_YT_DLP)
        }

        assertSame(second, DownloadExecutorResolver(listOf(first, second)).resolve(request))
    }

    @Test
    fun rejectsDuplicateStrategyRegistrations() {
        val first: DownloadExecutor = mockk {
            every { strategies } returns setOf(DownloadStrategy.VK_YT_DLP)
        }
        val second: DownloadExecutor = mockk {
            every { strategies } returns setOf(DownloadStrategy.VK_YT_DLP)
        }

        assertFailsWith<IllegalArgumentException> {
            DownloadExecutorResolver(listOf(first, second))
        }
    }

    @Test
    fun rejectsUnregisteredStrategy() {
        assertFailsWith<IllegalStateException> {
            DownloadExecutorResolver(emptyList()).resolve(request())
        }
    }

    private fun request(): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            outputType = OutputType.VIDEO,
            strategy = DownloadStrategy.VK_YT_DLP,
            formatSelector = "format",
            presetName = "preset",
        )
    }
}
