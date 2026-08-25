package com.nkudrin713.kradnik.download.cover

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CoverDownloaderTest {
    private val server = HttpServer.create(InetSocketAddress(0), 0).apply { start() }
    private val downloader = CoverDownloader(TelegramUploadLimits(2_000_000_000, localMode = true))

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun downloadsSupportedImage(@TempDir tempDir: Path) = runTest {
        val body = "image".toByteArray()
        server.createContext("/cover") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val actual = downloader.download(url("/cover"), tempDir)

        assertContentEquals(body, actual.file.readBytes())
    }

    @Test
    fun rejectsNonImageResponse(@TempDir tempDir: Path) = runTest {
        server.createContext("/text") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        assertFailsWith<CoverDownloadException> {
            downloader.download(url("/text"), tempDir)
        }
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"
}
