package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.choice.MediaChoiceBuilder
import com.nkudrin713.kradnik.download.choice.PostChoicePlanner
import com.nkudrin713.kradnik.download.choice.StandardMediaChoicePlanner
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.delivery.DeliveryContext
import com.nkudrin713.kradnik.download.delivery.MediaDelivery
import com.nkudrin713.kradnik.download.delivery.PlaylistDelivery
import com.nkudrin713.kradnik.download.delivery.ResultCache
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.domain.PlaylistItemFailure
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.playlist.AudioMessagesPlaylistWorkflow
import com.nkudrin713.kradnik.download.playlist.PlaylistChoicePlanner
import com.nkudrin713.kradnik.download.playlist.PlaylistEmptyException
import com.nkudrin713.kradnik.download.playlist.PlaylistEntryDownloader
import com.nkudrin713.kradnik.download.playlist.PlaylistWorkspaceBudget
import com.nkudrin713.kradnik.download.playlist.PlaylistZipBuilder
import com.nkudrin713.kradnik.download.playlist.ZipPlaylistWorkflow
import com.nkudrin713.kradnik.download.processing.DownloadJobProcessor
import com.nkudrin713.kradnik.download.processing.JobLifecycle
import com.nkudrin713.kradnik.download.processing.PlaylistJobProcessor
import com.nkudrin713.kradnik.download.progress.JobNotifications
import com.nkudrin713.kradnik.download.progress.JobProgress
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.JobStore
import com.nkudrin713.kradnik.download.single.PostHandler
import com.nkudrin713.kradnik.download.single.SingleMediaHandlers
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PlaylistSourceAdapter
import com.nkudrin713.kradnik.download.source.PostSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourcePost
import com.nkudrin713.kradnik.download.source.SourcePostItem
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceBoundaryTest {
    @Test
    fun customPostRunsThroughTheJobScenarioWithAnOpaqueDeliveryReceipt(@TempDir directory: Path) = runTest {
        val source = FixturePostSource()
        val engine = DownloadEngine(PlatformResolver(listOf(source)))
        val limits = TelegramUploadLimits(100)
        val handlers = SingleMediaHandlers(mockk(), mockk(), mockk(), mockk(), PostHandler(engine, DownloadPreflightService(AudioUploadPlanner(limits), limits), mockk()))
        val store = mockk<JobStore>()
        every { store.save(any()) } answers { firstArg<DownloadJob>().apply { id = 41 } }
        every { store.complete(41, "custom-post-receipt") } returns 1
        val jobs = DownloadJobService(store)
        val selected = source.select(source.spec, MediaSelection.Post("Text"))
        val job = requireNotNull(jobs.createJob(CreateDownloadJobCommand(1, 2, spec = selected)))
        val notifications = mockk<JobNotifications>(relaxed = true)
        every { notifications.forJob(job) } returns JobProgress {}
        val cache = mockk<ResultCache> { every { find(any()) } returns null }
        var content = emptyList<String>()
        val delivery = object : MediaDelivery {
            override suspend fun send(context: DeliveryContext, artifact: MediaArtifact): String {
                require(artifact is MediaArtifact.Post)
                assertEquals("Text", artifact.text)
                content = artifact.items.map { Files.readString(it.file) }
                return "custom-post-receipt"
            }
            override suspend fun sendCached(context: DeliveryContext, type: OutputType, receipt: String): String? = error("No cached result")
        }

        DownloadJobProcessor(handlers, cache, delivery, JobLifecycle(jobs, notifications), notifications, WorkDirCleaner(directory.toString())).process(job)

        assertEquals(listOf("first", "second", "first"), content)
        verify(exactly = 1) { store.complete(41, "custom-post-receipt") }
        verify(exactly = 1) { notifications.completed(job) }
        assertTrue(!Files.exists(directory.resolve("41")))
    }

    @ParameterizedTest
    @EnumSource(PlaylistDeliveryMode::class)
    fun customPlaylistRunsBothDeliveryModesWithoutTelegramOrJpa(mode: PlaylistDeliveryMode, @TempDir directory: Path) = runBlocking {
        val source = FixtureSource()
        val engine = DownloadEngine(PlatformResolver(listOf(source)))
        val initial = source.playlist("https://fixture.test/list", "320K")
        val selected = initial.copy(playlistDeliveryMode = mode, playlistEntries = initial.playlistEntries + initial.playlistEntries.single().copy(position = 2))
        val store = mockk<JobStore>()
        every { store.save(any()) } answers {
            firstArg<DownloadJob>().apply {
                id = 42
                status = DownloadJobStatus.PROCESSING
            }
        }
        val jobs = DownloadJobService(store)
        val job = requireNotNull(jobs.createJob(CreateDownloadJobCommand(1, 2, spec = selected)))
        every { store.findById(42) } returns Optional.of(job)
        every { store.savePlaylistResult(42, any()) } answers {
            synchronized(job) { job.playlistResults += secondArg<PlaylistAudioResult>() }
            true
        }
        every { store.savePlaylistFailures(42, any()) } returns true
        every { store.complete(42, "custom-playlist-receipt") } returns 1
        val notifications = mockk<JobNotifications>(relaxed = true)
        every { notifications.forJob(job) } returns JobProgress {}
        val delivery = mockk<PlaylistDelivery>()
        coEvery { delivery.checkStorage() } returns Unit
        coEvery { delivery.stagePlaylistAudio(any(), any()) } answers { "staged-${secondArg<PlaylistAudioEntry>().position}" }
        coEvery { delivery.sendPlaylistAudios(any(), any(), any()) } answers {
            assertEquals(listOf(1, 2), thirdArg<List<PlaylistAudioResult>>().sortedBy { it.position }.map { it.position })
            "custom-playlist-receipt"
        }
        coEvery { delivery.sendArchive(any(), any()) } answers {
            assertTrue(Files.isRegularFile(secondArg<Path>()))
            "custom-playlist-receipt"
        }
        val cache = mockk<ResultCache>(relaxed = true) {
            every { find(any()) } returns null
            every { findPlaylistAudio(any(), any()) } returns null
        }
        val limits = TelegramUploadLimits(10_000)
        val cleaner = WorkDirCleaner(directory.toString())
        val downloader = PlaylistEntryDownloader(engine, limits)
        val processor = PlaylistJobProcessor(
            AudioMessagesPlaylistWorkflow(jobs, downloader, delivery, cache, cleaner),
            ZipPlaylistWorkflow(jobs, downloader, PlaylistZipBuilder(), delivery, limits, cleaner, PlaylistWorkspaceBudget(limits)),
            JobLifecycle(jobs, notifications),
            notifications,
            cleaner,
        )

        processor.process(job)

        assertTrue(source.downloaded)
        verify(exactly = 1) { store.complete(42, "custom-playlist-receipt") }
        coVerify(exactly = if (mode == PlaylistDeliveryMode.AUDIO_MESSAGES) 1 else 0) { delivery.sendPlaylistAudios(any(), any(), any()) }
        coVerify(exactly = if (mode == PlaylistDeliveryMode.ZIP) 1 else 0) { delivery.sendArchive(any(), any()) }
        assertTrue(!Files.exists(directory.resolve("42")))
    }

    @Test
    fun customSourceSuppliesTypedItemFailureWithoutEnglishDiagnostics(@TempDir directory: Path) = runBlocking {
        val source = FixtureSource().apply {
            failure = DownloadFailure(DownloadFailureReason.SOURCE_UNAVAILABLE, "Произвольная диагностика", playlistFailure = PlaylistItemFailure.PRIVATE)
        }
        val store = mockk<JobStore>()
        every { store.save(any()) } answers {
            firstArg<DownloadJob>().apply {
                id = 43
                status = DownloadJobStatus.PROCESSING
            }
        }
        val jobs = DownloadJobService(store)
        val job = requireNotNull(jobs.createJob(CreateDownloadJobCommand(1, 2, spec = source.playlist("https://fixture.test/list", "320K"))))
        every { store.findById(43) } returns Optional.of(job)
        every { store.savePlaylistResult(43, any()) } answers {
            job.playlistResults += secondArg<PlaylistAudioResult>()
            true
        }
        val delivery = mockk<PlaylistDelivery> { coEvery { checkStorage() } returns Unit }
        val cache = mockk<ResultCache> {
            every { find(any()) } returns null
            every { findPlaylistAudio(any(), any()) } returns null
        }
        val limits = TelegramUploadLimits(10_000)
        val cleaner = WorkDirCleaner(directory.toString())
        val root = cleaner.create(43)
        val workflow = AudioMessagesPlaylistWorkflow(jobs, PlaylistEntryDownloader(DownloadEngine(PlatformResolver(listOf(source))), limits), delivery, cache, cleaner)

        val failure = assertFailsWith<PlaylistEmptyException> {
            workflow.run(43, DownloadRequestMapper.playlist(job), DeliveryContext.fromJob(job), root, JobProgress {})
        }

        assertEquals(PlaylistItemFailure.PRIVATE, failure.failures.single().failure)
        assertEquals("Произвольная диагностика", failure.failures.single().error)
        assertTrue(!Files.exists(root.resolve("1")))
        coVerify(exactly = 0) { delivery.stagePlaylistAudio(any(), any()) }
    }

    @Test
    fun adaptersOwnSettingsForOriginalPostsAndCollections() {
        val source = FixturePostSource()
        val messages = telegramMessages()
        val limits = TelegramUploadLimits(100)
        val choices = MediaChoiceBuilder(AudioUploadPlanner(limits), limits, messages, PlatformResolver(listOf(source)))

        val original = choices.videoOptions(source.spec, source.prepared.metadata, BotLanguage.EN, allowUnknownOriginalSize = true).single()
        val post = PostChoicePlanner(choices, messages).options(source.resolve("unused"), source.prepared, BotLanguage.EN).single()
        val collection = StandardMediaChoicePlanner(choices).videosOnly(source.spec, source.prepared.metadata, BotLanguage.EN, messages).single()

        assertEquals(listOf(MediaSelection.OriginalVideo, MediaSelection.Post("Text"), MediaSelection.VideoCollection), source.selections)
        assertEquals(listOf("native-original", "native-post", "native-collection"), listOf(original, post, collection).map { it.spec.presetName })
        assertEquals("fixture:choice:post:native-post", post.spec.cacheKey)
        assertEquals("Text", post.spec.postText)
    }

    @Test
    fun httpPostUsesCommonPreparationOrderingAndFileLimits(@TempDir directory: Path) = runTest {
        val source = FixturePostSource()
        val engine = DownloadEngine(PlatformResolver(listOf(source)))
        val limits = TelegramUploadLimits(100)
        val handler = PostHandler(engine, DownloadPreflightService(AudioUploadPlanner(limits), limits), mockk())
        val spec = source.select(source.spec, MediaSelection.Post("Text"))

        val artifact = handler.produce(DownloadRequestMapper.single(spec), directory, 1, JobProgress {}) as MediaArtifact.Post

        assertEquals("Text", artifact.text)
        assertEquals(listOf("first", "second", "first"), artifact.items.map { Files.readString(it.file) })
        assertEquals(listOf("first", "second", "first"), source.downloadedReferences)

        source.downloadedReferences.clear()
        val smallLimits = TelegramUploadLimits(10)
        val limitedHandler = PostHandler(engine, DownloadPreflightService(AudioUploadPlanner(smallLimits), smallLimits), mockk())
        assertFailsWith<DownloadRejectedException> {
            limitedHandler.produce(DownloadRequestMapper.single(spec), directory.resolve("limited"), 2, JobProgress {})
        }
        assertEquals(listOf("first", "second"), source.downloadedReferences)
    }

    @Test
    fun anotherSourceUsesCommonPlaylistPlanningAndFileLimits(@TempDir directory: Path) = runTest {
        val source = FixtureSource()
        val sources = PlatformResolver(listOf(source))
        val plan = assertNotNull(PlaylistChoicePlanner(sources, telegramMessages()).planOrNull("https://fixture.test/list", BotLanguage.EN))
        assertEquals(listOf("playlist_all", "playlist_all_zip"), plan.options.map { it.key })
        assertEquals("fixture-list:playlist_all", plan.options.first().spec.cacheKey)
        assertEquals(DownloadPlatform.VK, plan.options.first().spec.platform)
        assertEquals("320K", source.quality)
        val entry = plan.options.first().spec.playlistEntries.single()
        val downloader = PlaylistEntryDownloader(DownloadEngine(sources), TelegramUploadLimits(10))
        assertEquals("fixture:item", downloader.cacheKey(entry))
        assertEquals(3, downloader.download(entry, directory).sizeBytes)
        assertTrue(source.downloaded)
    }

    @Test
    fun duplicateRegistrationFailsInsteadOfReplacingAnAdapter() {
        val source = FixtureSource()
        assertFailsWith<IllegalArgumentException> { PlatformResolver(listOf(source, source)) }
    }

    @Test
    fun commonCodeDependsOnContractsAndSharedInfrastructureOnly() {
        val root = Path.of("src/main/kotlin/com/nkudrin713/kradnik")
        val services = setOf("instagram", "youtube", "vk", "threads")
        val forbidden = Regex("(?m)^import com\\.nkudrin713\\.kradnik\\.(ytdlp|download\\.(instagram|youtube|vk|threads))\\.")
        val command = Regex("\"--(audio|playlist|extractor|plugin|merge|embed|concat)[a-z-]*")
        val violations = mutableListOf<String>()
        Files.walk(root.resolve("download")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { path ->
                val owner = root.resolve("download").relativize(path).getName(0).toString()
                val code = Files.readString(path)
                if (owner !in services && (forbidden.containsMatchIn(code) || command.containsMatchIn(code))) violations += path.toString()
                if (owner in services) {
                    val coreDependency = Regex("(?m)^import com\\.nkudrin713\\.kradnik\\.(telegram|download\\.(processing|repository|service))\\.")
                    val otherAdapter = Regex("(?m)^import com\\.nkudrin713\\.kradnik\\.download\\.(instagram|youtube|vk|threads)\\.")
                        .findAll(code).any { it.groupValues[1] != owner }
                    if (coreDependency.containsMatchIn(code) || otherAdapter) violations += path.toString()
                }
                if (owner == "choice" && Regex("\\bpresetName\\s*=").containsMatchIn(code)) violations += path.toString()
            }
        }
        Files.walk(root.resolve("ytdlp")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { path ->
                if (Regex("download\\.(instagram|youtube|vk|threads)\\.").containsMatchIn(Files.readString(path))) violations += path.toString()
            }
        }
        assertEquals(emptyList(), violations, "Source rules leaked across package boundaries")
    }

    private class FixturePostSource : PostSourceAdapter {
        override val platform = DownloadPlatform.VK
        val spec = DownloadSpec("https://fixture.test/post", "https://fixture.test/post", "fixture", OutputType.VIDEO, platform, "opaque", presetName = "native")
        val selections = mutableListOf<MediaSelection>()
        val downloadedReferences = mutableListOf<String>()
        val prepared = object : PreparedPostSource {
            override val metadata = MediaMetadata(contentType = MediaContentType.CAROUSEL)
            override val post = SourcePost(listOf("first", "second", "first").map { SourcePostItem(PostMediaKind.PHOTO, it) }, "Text")
        }
        override fun supports(url: String): Boolean = url.startsWith("https://fixture.test/")
        override fun resolve(url: String): PlatformDownloadSpecs = PlatformDownloadSpecs(spec, spec)
        override fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec {
            selections += selection
            return when (selection) {
                MediaSelection.OriginalVideo -> spec.copy(presetName = "native-original")
                is MediaSelection.Post -> spec.copy(outputType = OutputType.POST, presetName = "native-post", postText = selection.text)
                MediaSelection.VideoCollection -> spec.copy(outputType = OutputType.POST, presetName = "native-collection")
                else -> error("Unsupported fixture selection")
            }
        }
        override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource = prepared
        override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile = error("No standalone downloads")
        override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
            val reference = prepared.post.items[index].reference
            downloadedReferences += reference
            val file = Files.writeString(directory.resolve("photo.jpg"), reference)
            return DownloadedFile(file, Files.size(file))
        }
    }

    private class FixtureSource : PlaylistSourceAdapter {
        override val platform = DownloadPlatform.VK
        var quality: String? = null
        var downloaded = false
        var failure: DownloadFailure? = null
        override fun supports(url: String): Boolean = url.startsWith("https://fixture.test/")
        override fun resolve(url: String): PlatformDownloadSpecs = error("This fixture only supplies playlists")
        override fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec = error("No single media choices")
        override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource = error("No single media")
        override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile = error("No single media")
        override suspend fun playlist(url: String, audioQuality: String): DownloadSpec {
            quality = audioQuality
            return DownloadSpec(url, url, "fixture-list", OutputType.AUDIO, platform, "opaque", presetName = "fixture", workloadType = DownloadWorkloadType.PLAYLIST_AUDIO, playlistEntries = listOf(PlaylistAudioEntry(1, "item", "https://fixture.test/item", "Title", 60, platform)))
        }
        override fun playlistItemUrl(entry: PlaylistAudioEntry): String = entry.url
        override fun playlistItemCacheKey(entry: PlaylistAudioEntry, source: SourceRequest?): String = "fixture:${entry.sourceId}"
        override suspend fun downloadPlaylistItem(entry: PlaylistAudioEntry, directory: Path, source: SourceRequest?): DownloadedFile {
            failure?.let { throw it }
            downloaded = true
            val file = Files.write(directory.resolve("audio.bin"), byteArrayOf(1, 2, 3))
            return DownloadedFile(file, Files.size(file))
        }
    }
}
