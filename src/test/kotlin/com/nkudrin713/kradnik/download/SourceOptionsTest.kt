package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.threads.ThreadsSourceAdapter
import com.nkudrin713.kradnik.download.youtube.YouTubeSourceAdapter
import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessExecutionResult
import com.nkudrin713.kradnik.process.ProcessRunner
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceOptionsTest {
    private val runner = mockk<ProcessRunner>()
    private val service = YtDlpService(runner)
    private val commands = mutableListOf<Command>()

    @Test
    fun youtubeOwnsProviderSettingsForMetadataPlaylistAndDownload(@TempDir directory: Path) = runTest {
        val adapter = YouTubeSourceAdapter(service, " http://provider:4416/ ")
        val request = DownloadRequestMapper.source(adapter.resolve("https://youtu.be/video").video)
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = """{"id":"video","title":"Title","entries":[{"id":"video"}]}""", timedOut = false, exitCode = 0)
        }
        val prepared = adapter.prepare(request, false)
        adapter.prepare(request, true)
        adapter.playlist("https://youtube.com/playlist?list=PL", "320K")
        val file = directory.resolve("video.mp4").apply { writeText("video") }
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = "KRADNIK_FILEPATH:\"$file\"", timedOut = false, exitCode = 0)
        }
        adapter.download(request, SourceMediaType.VIDEO, prepared, directory)
        assertEquals(4, commands.size)
        assertTrue(commands.all { "youtube:player_client=mweb" in it.args && "youtubepot-bgutilhttp:base_url=http://provider:4416" in it.args })
        assertTrue(commands.none { "--plugin-dirs" in it.args })
    }

    @Test
    fun youtubeAudioMetadataAndDownloadsUseDefaultClientsWithProvider(@TempDir directory: Path) = runTest {
        val adapter = YouTubeSourceAdapter(service, " http://provider:4416/ ")
        val url = "https://youtu.be/video"
        val request = DownloadRequestMapper.source(adapter.resolve(url).audio)
        val legacy = request.copy(presetName = "default_audio", extraArgs = listOf("-x", "--audio-format", "mp3"))
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = """{"id":"video","title":"Title"}""", timedOut = false, exitCode = 0)
        }
        val prepared = adapter.prepare(request, false)
        adapter.prepare(legacy, false)
        val file = directory.resolve("audio.mp3").apply { writeText("audio") }
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = "KRADNIK_FILEPATH:\"$file\"", timedOut = false, exitCode = 0)
        }
        adapter.download(request, SourceMediaType.AUDIO, prepared, directory)
        adapter.downloadPlaylistItem(PlaylistAudioEntry(1, "video", url, "Title", 120), directory, null)

        assertEquals(listOf("ba/bestaudio", "ba/bestaudio", "ba/bestaudio", "ba/bestaudio/best"), commands.map { it.args[it.args.indexOf("-f") + 1] })
        assertTrue(commands.all { "youtube:player_client=default" in it.args && "youtubepot-bgutilhttp:base_url=http://provider:4416" in it.args })
        assertTrue(commands.none { "youtube:player_client=mweb" in it.args })
    }

    @Test
    fun youtubeAudioFailureReportsDefaultClientWithoutProvider() = runTest {
        val adapter = YouTubeSourceAdapter(service)
        val request = DownloadRequestMapper.source(adapter.resolve("https://youtu.be/video").audio)
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stderr = "ERROR: Requested format is not available.", timedOut = false, exitCode = 1)
        }

        val failure = assertFailsWith<YtDlpException> { adapter.prepare(request, false) }
        assertTrue(failure.message.orEmpty().contains("client=default"))
        assertTrue(commands.single().args.none { it.startsWith("youtubepot-bgutilhttp:") })
    }

    @Test
    fun emptyYoutubeProviderAddsNoExtractorArguments() = runTest {
        val adapter = YouTubeSourceAdapter(service)
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = """{"title":"Title"}""", timedOut = false, exitCode = 0)
        }
        adapter.prepare(DownloadRequestMapper.source(adapter.resolve("https://youtu.be/video").video), false)
        assertFalse("--extractor-args" in commands.single().args)
    }

    @Test
    fun threadsOwnsPluginSettingsAndSelectsOneAlbumPosition(@TempDir directory: Path) = runTest {
        val adapter = ThreadsSourceAdapter(service, "custom-plugins")
        val request = DownloadRequestMapper.source(adapter.resolve("https://threads.net/t/POST").video)
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = """{"webpage_url":"https://threads.com/t/POST","entries":[{"id":"first"},{"id":"second"}]}""", timedOut = false, exitCode = 0)
        }
        val prepared = adapter.prepare(request, true)
        val file = directory.resolve("video.mp4").apply { writeText("video") }
        coEvery { runner.run(any()) } answers {
            commands += firstArg<Command>()
            ProcessExecutionResult(stdout = "KRADNIK_FILEPATH:\"$file\"", timedOut = false, exitCode = 0)
        }
        adapter.downloadPostItem(request, prepared, 1, directory)
        assertTrue(commands.all { it.args[it.args.indexOf("--plugin-dirs") + 1] == Path.of("custom-plugins").toAbsolutePath().toString() })
        assertTrue(commands.all { it.args[it.args.indexOf("--concat-playlist") + 1] == "never" })
        assertEquals("2", commands.last().args[commands.last().args.indexOf("--playlist-items") + 1])
        assertTrue(commands.none { it.args.any { arg -> arg.startsWith("youtube:") } })
    }
}
