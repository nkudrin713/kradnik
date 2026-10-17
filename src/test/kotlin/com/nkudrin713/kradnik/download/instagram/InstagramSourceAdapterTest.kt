package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.instagram.InstagramContentUnavailableException
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramSourceAdapter
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class InstagramSourceAdapterTest {
    private val instagram = mockk<InstagramEmbedDownloader>()
    private val ytDlp = mockk<YtDlpService>()
    private val request = mockk<SourceRequest>()
    private val adapter = InstagramSourceAdapter(instagram, ytDlp)

    @Test
    fun preservesSpecificFallbackFailureAndEmbedDiagnostic() = runTest {
        for (reason in listOf(DownloadFailureReason.AUTHENTICATION_REQUIRED, DownloadFailureReason.SOURCE_RATE_LIMITED, DownloadFailureReason.SOURCE_TIMEOUT, DownloadFailureReason.SOURCE_UNAVAILABLE)) {
            val embed = InstagramContentUnavailableException()
            val fallback = YtDlpException("Detailed fallback error", reason)
            coEvery { instagram.prepare(request) } throws embed
            coEvery { ytDlp.extractImageMetadata(request) } throws fallback

            val actual = assertFailsWith<YtDlpException> { adapter.prepare(request, catalog = true) }

            assertSame(fallback, actual)
            assertSame(embed, actual.suppressed.single())
        }
    }

    @Test
    fun unknownFallbackFailureKeepsOriginalUnavailableReason() = runTest {
        val embed = InstagramContentUnavailableException()
        val fallback = YtDlpException("Unknown failure")
        coEvery { instagram.prepare(request) } throws embed
        coEvery { ytDlp.extractImageMetadata(request) } throws fallback

        val actual = assertFailsWith<InstagramContentUnavailableException> { adapter.prepare(request, catalog = true) }

        assertSame(embed, actual)
        assertSame(fallback, actual.suppressed.single())
    }
}
