package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdkInstagramHttpClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUri: URI
    private val client = JdkInstagramHttpClient()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUri = URI.create("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun readsSuccessfulTextResponse() = runTest {
        server.createContext("/embed") { exchange ->
            val body = "embed body".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        assertEquals("embed body", client.getText(baseUri.resolve("/embed")))
    }

    @Test
    fun downloadsVideoResponse(@TempDir tempDir: Path) = runTest {
        val body = byteArrayOf(1, 2, 3, 4)
        server.createContext("/video") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val outputFile = tempDir.resolve("video.mp4")

        val downloaded = client.download(baseUri.resolve("/video"), outputFile)

        assertEquals(outputFile, downloaded.file)
        assertEquals(body.size.toLong(), downloaded.sizeBytes)
        assertContentEquals(body, Files.readAllBytes(outputFile))
    }

    @Test
    fun rejectsNonVideoResponse(@TempDir tempDir: Path) = runTest {
        server.createContext("/html") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        assertFailsWith<InstagramEmbedException> {
            client.download(baseUri.resolve("/html"), tempDir.resolve("video.mp4"))
        }
    }

    @Test
    fun rejectsVideoWhenContentLengthExceedsLimit(@TempDir tempDir: Path) = runTest {
        val body = byteArrayOf(1, 2, 3, 4)
        server.createContext("/large-video") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val outputFile = tempDir.resolve("video.mp4")
        val limitedClient = JdkInstagramHttpClient(
            TelegramUploadLimits(maxUploadBytes = 3, localMode = true)
        )

        val error = assertFailsWith<InstagramMediaTooLargeException> {
            limitedClient.download(baseUri.resolve("/large-video"), outputFile)
        }

        assertEquals(body.size.toLong(), error.sizeBytes)
        assertEquals(false, Files.exists(outputFile))
    }

    @Test
    fun stopsChunkedVideoWhenStreamExceedsLimit(@TempDir tempDir: Path) = runTest {
        val body = byteArrayOf(1, 2, 3, 4)
        server.createContext("/chunked-video") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body) }
        }
        val outputFile = tempDir.resolve("video.mp4")
        val limitedClient = JdkInstagramHttpClient(
            TelegramUploadLimits(maxUploadBytes = 3, localMode = true)
        )

        assertFailsWith<InstagramMediaTooLargeException> {
            limitedClient.download(baseUri.resolve("/chunked-video"), outputFile)
        }

        assertEquals(false, Files.exists(outputFile))
    }

    @Test
    fun rejectsFailedTextResponse() = runTest {
        server.createContext("/failed") { exchange ->
            exchange.responseHeaders.add("Retry-After", "60")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }

        val error = assertFailsWith<InstagramHttpException> {
            client.getText(baseUri.resolve("/failed"))
        }

        assertEquals(InstagramRequestStage.EMBED, error.stage)
        assertEquals(429, error.statusCode)
        assertEquals(Duration.ofSeconds(60), error.retryAfter)
    }
}
