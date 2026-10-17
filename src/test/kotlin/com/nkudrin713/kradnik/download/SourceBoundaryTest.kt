package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.playlist.PlaylistChoicePlanner
import com.nkudrin713.kradnik.download.playlist.PlaylistEntryDownloader
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PlaylistSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceBoundaryTest {
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
            }
        }
        Files.walk(root.resolve("ytdlp")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { path ->
                if (Regex("download\\.(instagram|youtube|vk|threads)\\.").containsMatchIn(Files.readString(path))) violations += path.toString()
            }
        }
        assertEquals(emptyList(), violations, "Source rules leaked across package boundaries")
    }

    private class FixtureSource : PlaylistSourceAdapter {
        override val platform = DownloadPlatform.VK
        var quality: String? = null
        var downloaded = false
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
            downloaded = true
            val file = Files.write(directory.resolve("audio.bin"), byteArrayOf(1, 2, 3))
            return DownloadedFile(file, Files.size(file))
        }
    }
}
