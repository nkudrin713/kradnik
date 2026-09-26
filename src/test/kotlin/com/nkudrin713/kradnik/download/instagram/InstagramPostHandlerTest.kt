package com.nkudrin713.kradnik.download.instagram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.single.InstagramPostHandler
import com.nkudrin713.kradnik.download.source.InstagramPreparedSource
import com.nkudrin713.kradnik.download.source.InstagramSourceAdapter
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstagramPostHandlerTest {
    private val source: InstagramSourceAdapter = mockk()
    private val videos: TelegramVideoPreparer = mockk()
    private val limits = TelegramUploadLimits(1_000)
    private val handler = InstagramPostHandler(source, DownloadPreflightService(AudioUploadPlanner(limits), limits), videos)
    private val sourceRequest = SourceRequest("https://www.instagram.com/p/TEST/", "https://www.instagram.com/p/TEST/", DownloadPlatform.INSTAGRAM, "best", presetName = "instagram")
    private val request = SingleMediaRequest(sourceRequest, OutputType.POST, "key")
    private val metadata = jacksonObjectMapper().readValue<YtDlpMetadataDto>("{}")
    private val prepared = InstagramPreparedSource(
        InstagramPreparedDownload(
            "TEST",
            null,
            metadata = metadata,
            items = listOf(InstagramMedia(PostMediaKind.VIDEO, null), InstagramMedia(PostMediaKind.PHOTO, null), InstagramMedia(PostMediaKind.VIDEO, null)),
        ),
    )

    @Test
    fun preparesEachVideoInItsOwnDirectoryAndPreservesOrder(@TempDir dir: Path) = runTest {
        coEvery { source.prepare(sourceRequest, false) } returns prepared
        coEvery { source.downloadPostItem(sourceRequest, prepared, any(), any()) } answers {
            DownloadedFile(arg<Path>(3).resolve("source"), 100)
        }
        coEvery { videos.prepare(any(), any(), 1) } answers { DownloadedFile(arg<Path>(1).resolve("telegram-video.mp4"), 120) }
        val result = handler.produce(request, dir, 1, JobProgress {}) as MediaArtifact.Post
        assertEquals(listOf(PostMediaKind.VIDEO, PostMediaKind.PHOTO, PostMediaKind.VIDEO), result.items.map { it.kind })
        assertEquals(listOf("item-1", "item-2", "item-3"), result.items.map { it.file.parent.fileName.toString() })
        coVerify(exactly = 2) { videos.prepare(any(), any(), 1) }
    }

    @Test
    fun boundsTotalPostSizeAndDoesNotContinueAfterCancellation(@TempDir dir: Path) = runTest {
        coEvery { source.prepare(sourceRequest, false) } returns prepared
        coEvery { source.downloadPostItem(any(), any(), any(), any()) } answers { DownloadedFile(arg<Path>(3).resolve("source"), 600) }
        coEvery { videos.prepare(any(), any(), any()) } answers { firstArg() }
        assertFailsWith<DownloadRejectedException> { handler.produce(request, dir, 1, JobProgress {}) }
        coVerify(exactly = 0) { source.downloadPostItem(any(), any(), 2, any()) }
        coEvery { source.downloadPostItem(any(), any(), 0, any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { handler.produce(request, dir, 1, JobProgress {}) }
    }

    @Test
    fun ytDlpFallbackSelectsOnlyTheRequestedCarouselPosition(@TempDir dir: Path) = runTest {
        val ytDlp = mockk<YtDlpService>()
        val adapter = InstagramSourceAdapter(mockk(), ytDlp)
        val file = DownloadedFile(dir.resolve("video.mp4"), 100)
        coEvery { ytDlp.download(any(), dir) } returns file
        assertEquals(file, adapter.downloadPostItem(sourceRequest, prepared, 2, dir))
        coVerify { ytDlp.download(sourceRequest.copy(extraArgs = listOf("--playlist-items", "3")), dir) }
    }
}
