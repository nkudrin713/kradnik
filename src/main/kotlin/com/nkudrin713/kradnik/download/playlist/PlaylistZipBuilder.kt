package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PlaylistLocalFile(val entry: PlaylistAudioEntry, val path: Path)

@Component
class PlaylistZipBuilder {
    suspend fun build(files: List<PlaylistLocalFile>, title: String?, root: Path, maxBytes: Long): Path = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "Cannot create an empty playlist archive" }
        require(files.map { it.entry.position }.distinct().size == files.size) { "Duplicate playlist positions" }
        val context = currentCoroutineContext()
        val temporary = root.resolve("playlist.zip.part")
        ZipOutputStream(LimitedOutputStream(Files.newOutputStream(temporary).buffered(), maxBytes)).use { zip ->
            // MP3 is already compressed; avoid spending CPU on compression.
            zip.setLevel(Deflater.NO_COMPRESSION)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            for ((entry, path) in files.sortedBy { it.entry.position }) {
                context.ensureActive()
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Playlist entry is not a regular file" }
                val name = "${entry.position.toString().padStart(3, '0')} – ${safeName(entry.title, "Audio")}.mp3"
                zip.putNextEntry(ZipEntry(name))
                Files.newInputStream(path).use { input ->
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        context.ensureActive()
        val archive = root.resolve("${files.size} – ${safeName(title, "YouTube playlist")}.zip")
        Files.move(temporary, archive)
    }

    private fun safeName(value: String?, fallback: String): String {
        val cleaned = value.orEmpty().replace(Regex("[\\p{Cc}\\p{Cf}\\\\/:*?\"<>|]"), "_").trim().trim('.')
        var bytes = 0
        val bounded = buildString {
            for (codePoint in cleaned.codePoints().toArray()) {
                val character = String(Character.toChars(codePoint))
                bytes += character.toByteArray(Charsets.UTF_8).size
                if (bytes > 180) break
                append(character)
            }
        }.trim().trim('.')
        return bounded.ifBlank { fallback }
    }

    /** Counts ZIP headers and the central directory as well as the media payload. */
    private class LimitedOutputStream(private val target: OutputStream, private val limit: Long) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            reserve(1)
            target.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            target.write(bytes, offset, length)
        }

        private fun reserve(length: Int) {
            if (length > limit - written) throw PlaylistSizeLimitException()
            written += length
        }

        override fun close() = target.close()
    }
}
