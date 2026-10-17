package com.nkudrin713.kradnik.download.threads

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.progress.JobProgress
import com.nkudrin713.kradnik.download.single.PostHandler
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.testPlatformResolver
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

class ThreadsSourceAdapterTest {
    private val ytDlp = mockk<YtDlpService>()
    private val adapter = ThreadsSourceAdapter(ytDlp)
    private val engine = DownloadEngine(PlatformResolver(listOf(adapter)))
    private val source = DownloadRequestMapper.source(testPlatformResolver().resolve("https://threads.com/t/POST").video)
    private val metadata = jacksonObjectMapper().readValue<YtDlpMetadataDto>(
        """{"id":"POST","webpage_url":"https://www.threads.com/t/POST/","entries":[{"id":"POST_6"},{"id":"POST_9"}]}""",
    )

    @Test
    fun downloadsFilteredVideosByOrdinalAndPreparesSeparateFiles(@TempDir directory: Path) = runTest {
        coEvery { ytDlp.extractPostMetadata(source, false, any()) } returns metadata
        coEvery { ytDlp.download(any(), any(), any()) } answers { DownloadedFile(arg<Path>(1).resolve("video.mp4"), 100) }
        val videos = mockk<TelegramVideoPreparer>()
        coEvery { videos.prepare(any(), any(), 1) } answers { firstArg() }
        val limits = TelegramUploadLimits(1_000)
        val handler = PostHandler(engine, DownloadPreflightService(AudioUploadPlanner(limits), limits), videos)

        val result = handler.produce(SingleMediaRequest(source, OutputType.POST, "key"), directory, 1, JobProgress {}) as MediaArtifact.Post

        assertEquals(listOf("item-1", "item-2"), result.items.map { it.file.parent.fileName.toString() })
        coVerify { ytDlp.download(source.copy(extraArgs = source.extraArgs + listOf("--playlist-items", "1")), directory.resolve("item-1"), any()) }
        coVerify { ytDlp.download(source.copy(extraArgs = source.extraArgs + listOf("--playlist-items", "2")), directory.resolve("item-2"), any()) }
    }

    @Test
    fun singleVideoPathRejectsCarouselInsteadOfReturningItsLastFile(@TempDir directory: Path) = runTest {
        coEvery { ytDlp.extractPostMetadata(source, false, any()) } returns metadata
        val prepared = engine.prepare(source)
        assertFailsWith<IllegalArgumentException> { engine.download(source, SourceMediaType.VIDEO, prepared, directory) }
        coVerify(exactly = 0) { ytDlp.download(any(), any(), any()) }
    }

    @Test
    fun albumKeepsExistingTotalSizeAndCancellationLimits(@TempDir directory: Path) = runTest {
        coEvery { ytDlp.extractPostMetadata(source, false, any()) } returns metadata
        coEvery { ytDlp.download(any(), any(), any()) } answers { DownloadedFile(arg<Path>(1).resolve("video.mp4"), 600) }
        val videos = mockk<TelegramVideoPreparer>()
        coEvery { videos.prepare(any(), any(), 1) } answers { firstArg() }
        val limits = TelegramUploadLimits(1_000)
        val handler = PostHandler(engine, DownloadPreflightService(AudioUploadPlanner(limits), limits), videos)
        val request = SingleMediaRequest(source, OutputType.POST, "key")
        assertFailsWith<DownloadRejectedException> { handler.produce(request, directory, 1, JobProgress {}) }
        coEvery { ytDlp.download(any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { handler.produce(request, directory, 1, JobProgress {}) }
    }
}
