package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistZipBuilderTest {
    @TempDir lateinit var root: Path
    private val builder = PlaylistZipBuilder()

    @Test
    fun preservesOrderContentAndDuplicateTitlesWithSafeUnicodeNames() = runTest {
        val second = localFile(3, "../Трек\\:тест", byteArrayOf(3, 4))
        val first = localFile(1, "../Трек\\:тест", byteArrayOf(1, 2))

        val archive = builder.build(listOf(second, first), "Мой/плейлист", root, 10_000)

        assertEquals("2 – Мой_плейлист.zip", archive.fileName.toString())
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("001 – _Трек__тест.mp3", "003 – _Трек__тест.mp3"), entries.map { it.name })
            assertContentEquals(byteArrayOf(1, 2), zip.getInputStream(entries[0]).readAllBytes())
            assertContentEquals(byteArrayOf(3, 4), zip.getInputStream(entries[1]).readAllBytes())
        }
    }

    @Test
    fun countsHeadersAgainstUploadLimit() = runTest {
        val file = localFile(1, "Track", ByteArray(100))
        assertFailsWith<PlaylistSizeLimitException> { builder.build(listOf(file), "Playlist", root, 100) }
        assertFalse(Files.exists(root.resolve("1 – Playlist.zip")))
    }

    @Test
    fun boundsUtf8FilenamesAndUsesFallbackTitle() = runTest {
        val file = localFile(1, "🎵".repeat(150), byteArrayOf(1))
        val archive = builder.build(listOf(file), " ", root, 10_000)
        assertEquals("1 – YouTube playlist.zip", archive.fileName.toString())
        ZipFile(archive.toFile()).use { zip ->
            assertTrue(zip.entries().nextElement().name.toByteArray().size < 255)
        }
        val longTitle = builder.build(listOf(file), "Я".repeat(500), root, 10_000)
        assertTrue(longTitle.fileName.toString().toByteArray().size < 255)
    }

    @Test
    fun rejectsEmptyArchivesAndSymbolicLinks() = runTest {
        assertFailsWith<IllegalArgumentException> { builder.build(emptyList(), "Empty", root, 1000) }
        val file = localFile(1, "Track", byteArrayOf(1))
        val link = Files.createSymbolicLink(root.resolve("link.mp3"), file.path)
        assertFailsWith<IllegalArgumentException> { builder.build(listOf(file.copy(path = link)), "Links", root, 1000) }
    }

    private fun localFile(position: Int, title: String, bytes: ByteArray): PlaylistLocalFile {
        val path = Files.write(root.resolve("$position.mp3"), bytes)
        return PlaylistLocalFile(PlaylistAudioEntry(position, "video-$position", "https://youtu.be/video-$position", title, 60), path)
    }
}
